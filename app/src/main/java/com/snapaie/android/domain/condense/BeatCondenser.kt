package com.snapaie.android.domain.condense

import com.snapaie.android.data.ai.ModelSessionManager
import com.snapaie.android.domain.scan.PromptLibrary
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
    private val sessionManager: ModelSessionManager,
    private val promptLibrary: PromptLibrary,
) {

    suspend fun condense(
        sourceText: String,
        ledger: StoryLedger,
        previousTail: String,
        budgetWords: Int,
        onToken: (String) -> Unit = {},
    ): CondensedBeat {
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

    private suspend fun generate(prompt: String, onToken: (String) -> Unit): String? =
        withTimeoutOrNull(BEAT_TIMEOUT_MS) {
            val builder = StringBuilder()
            sessionManager.stream(prompt).collect { chunk ->
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
        val template = runCatching { promptLibrary.readAsset("prompts/condense.md") }.getOrDefault("")
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
            .replace("{{SOURCE}}", sourceText.take(MAX_SOURCE_CHARS))

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

    /**
     * Last resort: an extractive condensation, no model involved.
     *
     * Keeps the opening of every paragraph in order and drops the tail of each, which
     * preserves sequence, names and events at the cost of prose quality. It reads worse
     * than a real retelling — but a rough paragraph in the right place is recoverable,
     * whereas a hole in a story is not, and the reader is shown which passages these are so
     * they can be re-run later.
     */
    fun extractiveFallback(sourceText: String, budgetWords: Int): String {
        val paragraphs = sourceText.split(Regex("""\n\s*\n""")).map { it.trim() }.filter { it.isNotBlank() }
        if (paragraphs.isEmpty()) return sourceText.trim()

        val budget = budgetWords.coerceAtLeast(BudgetGovernor.MIN_BEAT_WORDS)
        val perParagraph = (budget / paragraphs.size).coerceAtLeast(MIN_FALLBACK_WORDS_PER_PARAGRAPH)

        return paragraphs.joinToString("\n\n") { paragraph ->
            val sentences = splitSentences(paragraph)
            val kept = StringBuilder()
            var words = 0
            for (sentence in sentences) {
                if (words >= perParagraph && kept.isNotEmpty()) break
                if (kept.isNotEmpty()) kept.append(' ')
                kept.append(sentence.trim())
                words += countWords(sentence)
            }
            kept.toString()
        }.trim()
    }

    private fun splitSentences(text: String): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        var index = 0
        while (index < text.length) {
            if (text[index] in ".!?") {
                var end = index + 1
                while (end < text.length && text[end] in "\"')]\u2019\u201D") end++
                if (end >= text.length || text[end].isWhitespace()) {
                    result += text.substring(start, end)
                    start = end
                    index = end
                    continue
                }
            }
            index++
        }
        if (start < text.length) result += text.substring(start)
        return result.map { it.trim() }.filter { it.isNotBlank() }
    }

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

        const val MAX_SOURCE_CHARS = 9_000
        const val PREVIOUS_TAIL_CHARS = 700
        const val MIN_FALLBACK_WORDS_PER_PARAGRAPH = 12
    }
}
