package com.snapaie.android.domain.condense

import com.snapaie.android.data.ai.ModelSessionManager
import com.snapaie.android.data.ai.TextGenerator
import com.snapaie.android.domain.scan.PromptBudget
import com.snapaie.android.domain.scan.PromptSource
import kotlinx.coroutines.withTimeoutOrNull

/** One beat's finished text and the ledger to carry into the next. */
data class CondensedBeat(
    val prose: String,
    val ledger: StoryLedger,
    val words: Int,
    val attempts: Int,
    /** True when the model never produced acceptable prose and the extractive path ran. */
    val usedFallback: Boolean,
    val lastRejection: BeatRejection = BeatRejection.NONE,
    /** True when the beat was shortened by cutting sentences rather than rewriting them. */
    val wasAbridged: Boolean = false,
)

/**
 * Condenses one beat, with a retry ladder and a guaranteed floor.
 *
 * The floor is the point. A whole-book run makes thousands of model calls, and over that
 * many attempts something will eventually come back empty, truncated, or as a summary. If
 * any one of those could fail the beat, a 500-page book would end up with holes in it —
 * which is the exact failure this product exists to avoid. So the last resort is not an
 * error, it is [extractiveFallback]: clumsier prose, but the events are still there and in
 * order, and the reader is told which passages it happened to.
 */
class BeatCondenser(
    private val sessionManager: TextGenerator,
    private val promptLibrary: PromptSource,
) {

    suspend fun condense(
        sourceText: String,
        ledger: StoryLedger,
        previousTail: String,
        budgetWords: Int,
        onToken: (String) -> Unit = {},
    ): CondensedBeat {
        // First, try to shorten the beat by cutting sentences rather than rewriting them.
        // A retelling is a summary wearing the book's clothes; an abridgement is the book,
        // shorter. When deletion lands we are done, and it costs one short reply instead
        // of several hundred generated words.
        abridge(sourceText, ledger, budgetWords, onToken)?.let { return it }

        var attempt = 0
        var lastRejection = BeatRejection.NONE

        while (attempt < MAX_ATTEMPTS) {
            attempt++
            // Each retry widens the allowance: TOO_SHORT is by far the most common
            // rejection, and asking again for the same number tends to get it again.
            val budget = (budgetWords * retryMultiplier(attempt)).toInt()
                .coerceAtLeast(BudgetGovernor.MIN_BEAT_WORDS)

            val raw = generate(
                prompt = buildPrompt(sourceText, ledger, previousTail, budget, attempt),
                onToken = onToken,
            ) ?: run {
                lastRejection = BeatRejection.EMPTY
                continue
            }

            val response = BeatContract.split(raw)
            val rejection = BeatContract.evaluate(response.prose, sourceText, budgetWords)
            if (rejection == BeatRejection.NONE) {
                return CondensedBeat(
                    prose = response.prose,
                    ledger = response.ledgerPatch?.let { ledger.merge(it) } ?: ledger,
                    words = countWords(response.prose),
                    attempts = attempt,
                    usedFallback = false,
                )
            }
            lastRejection = rejection
        }

        val fallback = extractiveFallback(sourceText, budgetWords)
        return CondensedBeat(
            prose = fallback,
            ledger = ledger,
            words = countWords(fallback),
            attempts = attempt,
            usedFallback = true,
            lastRejection = lastRejection,
        )
    }

    /**
     * Shortens the beat by choosing which of the author's sentences survive.
     *
     * Returns null when deletion cannot do the job — no template, no model, a passage
     * already inside its budget is returned as-is, and a reply that yields too little text
     * falls through to the retelling ladder rather than shipping a gutted beat.
     */
    private suspend fun abridge(
        sourceText: String,
        ledger: StoryLedger,
        budgetWords: Int,
        onToken: (String) -> Unit,
    ): CondensedBeat? {
        val template = runCatching { promptLibrary.read("prompts/abridge.md") }
            .getOrDefault("")
        if (template.isBlank()) return null

        val sentences = Abridger.split(sourceText)
        if (sentences.isEmpty()) return null

        // A beat can be larger than the window, so it is walked a run at a time rather
        // than refused. Nothing needs to carry between runs — no voice to keep, nothing to
        // re-introduce — because none of it is being rewritten.
        val room = PromptBudget.maxSourceChars(template, ModelSessionManager.DEFAULT_MAX_OUTPUT_TOKENS)
        val walk = ChunkedAbridgement.keepIndices(
            sentences = sentences,
            targetWords = budgetWords,
            maxChunkChars = room,
        ) { numbered, count, runTarget ->
            val prompt = template
                .replace("{{TARGET_WORDS}}", runTarget.toString())
                .replace("{{SENTENCES}}", numbered)
            generate(prompt, onToken)?.takeIf { count > 0 }
        }
        if (walk.keep.isEmpty()) return null

        val assembled = Abridger.assemble(sentences, walk.keep)
        val words = Abridger.countWords(assembled)
        // Too aggressive a cut is worse than a retelling: let the ladder try instead.
        if (words < budgetWords * MIN_ABRIDGED_RATIO) return null

        return CondensedBeat(
            prose = assembled,
            // Nothing was rewritten, so there is no new naming or invented detail for the
            // ledger to guard against downstream.
            ledger = ledger,
            words = words,
            attempts = 1,
            // A run the model could not answer was chosen locally, and the reader is told.
            usedFallback = walk.usedFallback,
            wasAbridged = true,
        )
    }

    private suspend fun generate(prompt: String, onToken: (String) -> Unit): String? =
        withTimeoutOrNull(BEAT_TIMEOUT_MS) {
            val builder = StringBuilder()
            sessionManager.stream(prompt, ModelSessionManager.DEFAULT_MAX_OUTPUT_TOKENS)
                .collect { chunk ->
                    builder.append(chunk)
                    onToken(chunk)
                }
            builder.toString().takeIf { it.isNotBlank() }
        }

    private fun buildPrompt(
        sourceText: String,
        ledger: StoryLedger,
        previousTail: String,
        budgetWords: Int,
        attempt: Int,
    ): String {
        val template = runCatching { promptLibrary.read("prompts/condense.md") }.getOrDefault("")
        val filled = template
            .replace("{{TARGET_WORDS}}", budgetWords.toString())
            .replace("{{DELIMITER}}", BeatContract.LEDGER_DELIMITER)
            .replace(
                "{{LEDGER}}",
                ledger.render().ifBlank { "This is the opening of the book. Nothing has happened yet." },
            )
            .replace(
                "{{PREVIOUS_TAIL}}",
                previousTail.takeLast(PREVIOUS_TAIL_CHARS).ifBlank { "(nothing yet)" },
            )
            .replace(
                "{{SOURCE}}",
                sourceText.take(minOf(MAX_SOURCE_CHARS, PromptBudget.maxSourceChars(template, budgetWords * 2))),
            )

        if (attempt <= 1) return filled
        return buildString {
            appendLine(filled)
            appendLine()
            appendLine("--- RETRY ---")
            appendLine(
                "Your previous attempt was rejected. Retell the passage as continuous story " +
                    "prose in the book's own voice. Do not describe the text, do not summarise " +
                    "it, do not use headings or bullets, and do not drop any event or name.",
            )
        }
    }

    /** Kept as a delegate so callers inside the pipeline read naturally. */
    fun extractiveFallback(sourceText: String, budgetWords: Int): String =
        ExtractiveCondenser.shorten(sourceText, budgetWords)

    private fun retryMultiplier(attempt: Int): Float = when (attempt) {
        1 -> 1.0f
        2 -> 1.25f
        else -> 1.5f
    }

    private fun countWords(text: String): Int = text.split(Regex("""\s+""")).count { it.isNotBlank() }

    private companion object {
        const val MAX_ATTEMPTS = 3

        /**
         * A beat is ~900 source words in and a few hundred out. On a slow CPU backend that
         * can take minutes, so the ceiling is generous — but finite, because a wedged
         * engine must not stall an overnight run indefinitely.
         */
        const val BEAT_TIMEOUT_MS = 6L * 60L * 1000L

        /**
         * How much of the budget an abridgement must reach to be accepted. Deletion that
         * lands far under the target has cut the beat to pieces; the retelling ladder is
         * a better answer than a hollowed-out passage.
         */
        const val MIN_ABRIDGED_RATIO = 0.45f

        const val MAX_SOURCE_CHARS = 9_000
        const val PREVIOUS_TAIL_CHARS = 700
        const val MIN_FALLBACK_WORDS_PER_PARAGRAPH = 12
    }
}
