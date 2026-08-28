package com.snapaie.android.domain.scan

import com.snapaie.android.data.ai.ModelSessionManager
import com.snapaie.android.data.ai.TextGenerator
import com.snapaie.android.data.model.BookScanDraft
import com.snapaie.android.data.model.KnowledgeResult
import com.snapaie.android.data.model.PhaseUpdate
import com.snapaie.android.data.model.ProducedBy
import com.snapaie.android.data.model.ScanPhase
import com.snapaie.android.domain.book.Segmenter
import com.snapaie.android.domain.condense.Abridger
import com.snapaie.android.domain.condense.ChunkedAbridgement
import com.snapaie.android.domain.condense.BeatContract
import com.snapaie.android.domain.condense.BeatRejection
import com.snapaie.android.domain.condense.BudgetGovernor
import com.snapaie.android.domain.condense.ExtractiveCondenser
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout

class WorkflowEngine(
    private val sessionManager: TextGenerator,
    private val prompts: PromptSource,
    private val scanPrompts: ScanPrompts,
    /**
     * Monotonic nanoseconds, injected so the snap's time budget can be tested.
     *
     * A test runs on virtual time; reading the wall clock from inside would mean the
     * deadline never expires under test and the guarantee it provides — that a snap always
     * finishes — would be asserted by a test that never exercises it.
     */
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private val parser = StructuredOutputParser()

    fun run(draft: BookScanDraft): Flow<WorkflowEvent> = flow {
        emit(WorkflowEvent.Phase(PhaseUpdate(ScanPhase.Capture, "Page text captured.", isComplete = true)))
        emit(WorkflowEvent.Phase(PhaseUpdate(ScanPhase.Ocr, "Page text ready.", isComplete = true)))

        emit(WorkflowEvent.Phase(PhaseUpdate(ScanPhase.Compression, "Condensing the page…")))

        // One model call, not two.
        //
        // This used to ask for a structured breakdown and then, separately, for the page
        // retold as prose — doubling the wait for a screen whose top half is the prose. The
        // retelling is what people came for, so it is the call that runs. The breakdown is
        // still available, on demand, from the result screen.
        // Held across the whole snap, not each call. A snap is several calls now, and the
        // gaps between them are exactly when a backgrounded app drops the weights.
        val attempt = sessionManager.acquireKeepAlive().use {
            condenseToProse(draft) { token -> emit(WorkflowEvent.Token(token)) }
        }

        // Having a model must never leave the reader worse off than not having one.
        //
        // This was the bug: with nothing installed the page was shortened locally and
        // always came back, while with a model installed a failed or rejected reply
        // produced an empty panel instead. Downloading two gigabytes made the feature stop
        // working — and it stopped for exactly the pages where the model struggled, which
        // are the ones that needed the fallback most. The local shortening is free and
        // always available; there was never a reason to withhold it here.
        val prose = attempt.prose.ifBlank { localShorten(draft) }
        val fromModel = attempt.prose.isNotBlank()

        emit(
            WorkflowEvent.Phase(
                PhaseUpdate(
                    ScanPhase.Compression,
                    // Say what happened. A blank panel with no explanation was the whole
                    // problem: it looked identical whether the model had failed, the page
                    // was too short, or the reply had been rejected.
                    when {
                        fromModel -> "Shorter version ready."
                        // Say which one they are reading. A local shortening that claims
                        // to be the model's work is the same dishonesty as a blank panel.
                        prose.isNotBlank() ->
                            attempt.reason?.let { "$it Shortened on the spot instead." }
                                ?: "Shortened on the spot."
                        else -> attempt.reason.orEmpty()
                    },
                    isComplete = true,
                ),
            ),
        )

        val producedBy = if (attempt.prose.isNotBlank()) attempt.producedBy else ProducedBy.ON_DEVICE
        val result = if (prose.isNotBlank()) {
            // The cheap, honest parts computed locally rather than asked for: no second
            // round trip, and nothing the model could invent.
            parser.heuristicOnly(draft).copy(condensedProse = prose, producedBy = producedBy)
        } else {
            parser.heuristicOnly(draft)
        }
        emit(WorkflowEvent.Result(finalize(draft, result), fromModel = fromModel))
    }

    /**
     * The structured breakdown — core idea, intent, vocabulary and the rest.
     *
     * Deliberately not part of a snap. It is a second full generation, and making every
     * page wait for it to reach the part that is actually read was the single biggest cost
     * in the flow. Callers ask for it when someone opens it.
     */
    fun breakdown(draft: BookScanDraft): Flow<WorkflowEvent> = flow {
        if (!sessionManager.isModelInstalled()) {
            emit(WorkflowEvent.Result(finalize(draft, parser.heuristicOnly(draft)), fromModel = false))
            return@flow
        }
        emit(WorkflowEvent.Phase(PhaseUpdate(ScanPhase.Insight, "Breaking the page down…")))

        var attempt = streamOnce(scanPrompts.buildScanPrompt(draft)) { token ->
            emit(WorkflowEvent.Token(token))
        }
        var outcome = parser.parse(attempt.text)
        if (outcome is ParseOutcome.Unparseable && attempt.text.isNotBlank()) {
            val retry = streamOnce(scanPrompts.buildRepairPrompt(draft, attempt.text)) { }
            val retryOutcome = parser.parse(retry.text)
            if (retryOutcome is ParseOutcome.Structured) {
                outcome = retryOutcome
                attempt = retry
            }
        }
        val result = when (outcome) {
            is ParseOutcome.Structured -> outcome.result
            ParseOutcome.Unparseable ->
                parser.plainTextOrHeuristic(draft, attempt.text, attempt.timeoutReason)
        }
        emit(WorkflowEvent.Phase(PhaseUpdate(ScanPhase.Insight, "Breakdown ready.", isComplete = true)))
        emit(WorkflowEvent.Result(finalize(draft, result), fromModel = true))
    }

    /**
     * Retells the page at roughly a third of its length, using the same contract the book
     * pipeline uses so a page and a chapter read the same way.
     */
    /** Shortens the page without a model, so a snap always produces something. */
    private fun localShorten(draft: BookScanDraft): String {
        val source = draft.pageText.trim()
        if (source.length < MIN_PROSE_SOURCE_CHARS) return ""
        return ExtractiveCondenser.shorten(
            source,
            budgetFor(Segmenter.countWords(source), draft.mode.condenseRatio),
        )
    }

    /**
     * Shortens by deleting sentences rather than rewriting them.
     *
     * This is what an abridged edition actually is, and it is why one still reads like the
     * book: every surviving sentence is the author's own, untouched. Nothing is
     * paraphrased, so nothing can be blunted, misattributed or invented.
     *
     * The device chooses first, and the model is asked to do better.
     *
     * That order is the whole point. [SentenceRanker] answers in well under a millisecond,
     * so a usable page exists before the model is even woken — and once it exists, nothing
     * the model does can leave the reader with nothing. It can be slow, it can fail to
     * load, it can return a reply that makes no sense; the page is already there. What it
     * cannot do any more is be the only thing standing between a snap and a result, which
     * is what it was every time this appeared to hang.
     *
     * When it does answer, its choice is used. A model reading the passage knows which
     * sentence carries the turn in a scene and which is scenery, and ranking by shared
     * vocabulary can only approximate that.
     */
    private suspend fun abridge(
        source: String,
        targetWords: Int,
        deadline: Deadline,
        onToken: suspend (String) -> Unit,
    ): String {
        val sentences = Abridger.split(source)
        if (sentences.isEmpty()) return ""
        if (sentences.sumOf { it.words } <= targetWords) return source.trim()

        val local = Abridger.assemble(sentences, Abridger.chooseLocally(sentences, targetWords))

        val template = runCatching { prompts.read("prompts/abridge.md") }.getOrDefault("")
        if (template.isBlank() || !sessionManager.isModelInstalled()) return local

        // Walked a window at a time, so the length of the page is not limited by how much
        // the model can read at once.
        val room = PromptBudget.maxSourceChars(template, ModelSessionManager.DEFAULT_MAX_OUTPUT_TOKENS)
        val walk = runCatching {
            ChunkedAbridgement.keepIndices(
                sentences = sentences,
                targetWords = targetWords,
                maxChunkChars = room,
            ) { numbered, count, runTarget ->
                if (deadline.expired) return@keepIndices null
                val prompt = template
                    .replace("{{TARGET_WORDS}}", runTarget.toString())
                    .replace("{{SENTENCES}}", numbered)
                // A list of indices is tiny, so the budget can be small; four tokens a
                // sentence is generous even if the model keeps every one of them.
                val budget = (count * 4 + 32).coerceAtMost(ModelSessionManager.DEFAULT_MAX_OUTPUT_TOKENS)
                streamOnce(prompt, budget, deadline, onToken).text.takeIf { it.isNotBlank() }
            }
        }.getOrNull() ?: return local

        if (walk.keep.isEmpty()) return local
        val assembled = Abridger.assemble(sentences, walk.keep)
        // A reply that deleted the passage rather than shortening it is not an improvement.
        if (Abridger.countWords(assembled) < targetWords / 3) return local
        return assembled
    }

    /**
     * The text a composing style is given to work from.
     *
     * A list or an analogy cannot be produced by deletion, so those styles have to be
     * written by the model — and the model can only read a window at a time. Truncating to
     * that window is the wrong answer: it silently throws away everything past the cut, so
     * a bulleted summary of a long capture would describe only its opening and give no
     * sign that the rest existed.
     *
     * Abridging first is the better one. The passage is walked in as many runs as it takes
     * and reduced with the author's own sentences until it fits, and only then is the list
     * composed. The result is one coherent list drawn from the whole capture rather than
     * several disconnected ones, or a faithful account of its first page.
     */
    private suspend fun materialFor(
        source: String,
        template: String,
        budgetTokens: Int,
        deadline: Deadline,
        onToken: suspend (String) -> Unit,
    ): String {
        val room = PromptBudget.maxSourceChars(template, budgetTokens)
        if (room <= 0) return source
        if (source.length <= room) return source
        // Aim slightly under the window so the reduced text is certain to fit.
        val targetWords = ((room * FIT_MARGIN) / AVERAGE_WORD_CHARS).toInt().coerceAtLeast(1)
        val reduced = abridge(source, targetWords, deadline, onToken)
        return if (reduced.isBlank()) source.take(room) else reduced.take(room)
    }

    /**
     * Words to aim for, floored gently.
     *
     * The book pipeline floors a passage at sixty words, which is right for a nine-hundred
     * word beat and wrong for a single page: on a short page every style hit the floor and
     * asked for the same length, so Concise and Detailed produced identical output and the
     * chips appeared to do nothing. The floor scales with the page instead, and only stops
     * at the point where a retelling would have nothing left to say.
     */
    private fun budgetFor(sourceWords: Int, ratio: Float): Int {
        val target = (sourceWords * ratio).toInt()
        val floor = minOf(BudgetGovernor.MIN_BEAT_WORDS, sourceWords / 3)
        return target.coerceAtLeast(floor).coerceAtLeast(ABSOLUTE_MIN_WORDS)
    }

    /** The retelling, plus why it is missing when it is. */
    private data class ProseAttempt(
        val prose: String,
        val reason: String?,
        val producedBy: ProducedBy = ProducedBy.ON_DEVICE,
    )

    /**
     * Retells the page shorter.
     *
     * Written as a ladder because every rung of it has been observed to fail on its own:
     * the image call can come back empty when the engine cannot serve vision, and a reply
     * can be perfectly good prose that trips the summary detector. Previously any of those
     * returned an empty string and the screen simply showed nothing, with no way to tell
     * which had happened. Now each fall-through is tried in turn and the last failure is
     * reported rather than swallowed.
     */
    private suspend fun condenseToProse(
        draft: BookScanDraft,
        onToken: suspend (String) -> Unit,
    ): ProseAttempt {
        var source = draft.pageText.trim()
        // Skipped outright once images have proven fatal here, so a device that cannot do
        // this goes straight to the text path instead of dying to find out again.
        val hasImage = sessionManager.visionAllowed &&
            draft.imagePath.isNotBlank() &&
            java.io.File(draft.imagePath).isFile
        val template = runCatching { prompts.read("prompts/condense_page.md") }.getOrDefault("")
        if (template.isBlank()) return ProseAttempt("", "The condenser is missing from this build.")

        var best = ""
        var reason: String? = null
        val deadline = startDeadline(SNAP_BUDGET_MS)

        // The page arrives already read. Capture transcribes with the model before any of
        // this runs, so there is no second reading pass here and no rule for deciding
        // between two readers — there is only one.
        if (source.length < MIN_PROSE_SOURCE_CHARS) {
            return ProseAttempt(
                "",
                if (hasImage && !sessionManager.isModelInstalled()) {
                    NEEDS_MODEL_TO_READ
                } else {
                    "There is not enough text on this page to shorten."
                },
            )
        }

        // Measured now, not before the photo was read.
        //
        // It used to be computed from whatever the recogniser returned, which on a
        // handwritten page is nothing — so the length was guessed at a typical page, the
        // guess was far larger than what the model actually transcribed, and the page came
        // back its original length because it already "fitted". How short to make something
        // cannot be decided before knowing what it says.
        val sourceWords = if (source.isNotBlank()) Segmenter.countWords(source) else TYPICAL_PAGE_WORDS
        // The chosen style decides how short, and how it reads. Both were previously
        // ignored here, which is why every style produced the same page.
        val budget = budgetFor(sourceWords, draft.mode.condenseRatio)
        val budgetTokens = budget * 2 + 80

        // 1. The model writes the page.
        //
        //    This is what the download is for. Selecting sentences on-device is fast and
        //    faithful, but what it produces is the page with parts removed — which reads
        //    as copied text, because it is copied text. Someone who spent 1.6 GB expects
        //    the model to have done something, and picking numbers is not something they
        //    can see. So when there is a model, it writes.
        if (source.length >= MIN_PROSE_SOURCE_CHARS &&
            sessionManager.isModelInstalled() &&
            !deadline.expired
        ) {
            val textPrompt = promptFor(template, budget, imageMode = false, style = draft.mode) +
                System.lineSeparator() + "The page:" + System.lineSeparator() +
                materialFor(source, template, budgetTokens, deadline, onToken)
            val raw = streamOnce(textPrompt, budgetTokens, deadline, onToken)
            val prose = BeatContract.cleanProse(raw.text)
            if (accepted(prose, source, budget, draft.mode)) {
                return ProseAttempt(prose, null, ProducedBy.OFFLINE_AI)
            }
            if (prose.length > best.length && !BeatContract.isRuntimeNoise(prose)) best = prose
            reason = raw.timeoutReason ?: reason ?: "The result did not come back as a retelling."
        }

        // 2. Keep the model's work even when it broke a rule.
        //
        //    Before falling back to deletion, take whatever the model actually wrote. A
        //    retelling rejected for running long or reading like a summary is still the
        //    model's prose, and still closer to what was asked for than a cut-down copy.
        if (best.length >= MIN_KEEPABLE_PROSE_CHARS) return ProseAttempt(best, null, ProducedBy.OFFLINE_AI)

        // 3. No model, or nothing usable from it: shorten by deletion instead.
        //
        //    Instant, and every surviving sentence is the author's own, so a page always
        //    comes back. This is the floor, not the plan.
        if (draft.mode.canAbridge && source.length >= MIN_PROSE_SOURCE_CHARS) {
            val abridged = abridge(source, budget, deadline, onToken)
            if (abridged.isNotBlank() && !BeatContract.isRuntimeNoise(abridged)) {
                return ProseAttempt(abridged, null)
            }
        }

        // 4. A list or an analogy has to be written, and there is nothing here to write it.
        //    The page is still shortened rather than refused: someone who asked for bullets
        //    and has no model downloaded is better served by the passage cut down than by
        //    an explanation of why they got nothing.
        if (source.length >= MIN_PROSE_SOURCE_CHARS) {
            val shortened = abridge(source, budget, deadline, onToken)
            if (shortened.isNotBlank()) {
                return ProseAttempt(
                    shortened,
                    if (sessionManager.isModelInstalled()) null else NEEDS_MODEL_FOR_STYLE,
                )
            }
        }

        // 4. No model output at all. Shorten it locally instead: the opening of each
        //    paragraph, in order, which keeps the sequence and the names at the cost of
        //    the prose. It is the difference between a rougher read and a blank screen,
        //    and it works with no model installed and no network.
        if (source.length >= MIN_PROSE_SOURCE_CHARS) {
            val extractive = ExtractiveCondenser.shorten(source, budget)
            if (extractive.length >= MIN_KEEPABLE_PROSE_CHARS) {
                return ProseAttempt(extractive, null)
            }
        }
        return ProseAttempt("", reason ?: "Nothing came back. Try again.")
    }

    private fun promptFor(
        template: String,
        budget: Int,
        imageMode: Boolean,
        style: com.snapaie.android.data.model.ExplainStyle,
    ): String = template
        .replace("{{TARGET_WORDS}}", budget.toString())
        .replace("{{STYLE}}", style.condenseInstruction)
        .replace(
            "{{SOURCE_BLOCK}}",
            if (imageMode) "The page is the attached image. Read it, then retell it." else "",
        )

    /**
     * Bullets and Steps are supposed to come back as lists, so the prose rules cannot
     * judge them — a list of short lines fails a check written for continuous narrative.
     * They are held to length and non-emptiness only.
     */
    private fun accepted(
        prose: String,
        source: String,
        budget: Int,
        style: com.snapaie.android.data.model.ExplainStyle,
    ): Boolean {
        if (prose.isBlank()) return false
        // Never let runtime failure text pass as a retelling, however long it is.
        if (BeatContract.isRuntimeNoise(prose)) return false
        if (style.isListStyle) return Segmenter.countWords(prose) >= budget / 4
        return BeatContract.evaluate(prose, source, budget) == BeatRejection.NONE
    }

    private data class StreamAttempt(val text: String, val timeoutReason: String?)

    /**
     * How long a whole snap may spend in the model.
     *
     * Every model call had its own four-minute ceiling and there was no budget for the
     * snap as a whole, so the ceilings added up. A photographed page could take a vision
     * pass, then an abridgement — which is now as many runs as the page needs — and then a
     * retelling, each free to run for four minutes on its own. That is the "condensing
     * forever, or never finishing" this replaces.
     *
     * One clock for the whole snap instead. Calls are cut short as it runs down, and once
     * it is spent the remaining work is done locally, which is instant and still returns
     * the author's own sentences. A page always comes back.
     */
    private class Deadline(private val endNanos: Long, private val nowNanos: () -> Long) {
        val expired: Boolean get() = nowNanos() >= endNanos

        val remainingMillis: Long
            get() = ((endNanos - nowNanos()) / 1_000_000L).coerceAtLeast(0L)

        /** The shorter of what this call is allowed and what the snap has left. */
        fun capFor(perCallMillis: Long): Long = minOf(perCallMillis, remainingMillis)
    }

    private fun startDeadline(millis: Long) =
        Deadline(nowNanos() + millis * 1_000_000L, nowNanos)

    /** Collects one full model stream (with timeout), forwarding tokens to [onToken]. */
    private suspend fun streamOnce(
        prompt: String,
        maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
        deadline: Deadline? = null,
        onToken: suspend (String) -> Unit,
    ): StreamAttempt {
        val accumulated = StringBuilder()
        var timeoutReason: String? = null
        val allowed = deadline?.capFor(INFERENCE_TIMEOUT_MS) ?: INFERENCE_TIMEOUT_MS
        if (allowed <= 0L) return StreamAttempt("", "There was not enough time left.")
        try {
            withTimeout(allowed) {
                sessionManager.stream(prompt, maxOutputTokens).collect { token ->
                    accumulated.append(token)
                    if (token.isNotBlank()) onToken(token)
                }
            }
        } catch (_: TimeoutCancellationException) {
            timeoutReason =
                "Local model stopped after ${INFERENCE_TIMEOUT_MS / 1000}s. Showing what it produced."
        } catch (error: IllegalStateException) {
            // The failure is reported through timeoutReason, never appended to the text.
            // Pasting it into the reply is how a runtime error string ended up printed
            // at the top of a retelling.
            timeoutReason = "That did not finish. Try again."
        }
        return StreamAttempt(accumulated.toString(), timeoutReason)
    }

    /** Overwrites model-invented metrics with honest, locally computed values. */
    private fun finalize(draft: BookScanDraft, result: KnowledgeResult): KnowledgeResult =
        result.copy(
            estimatedTimeSavedMinutes = ScanMetrics.minutesSavedForResult(draft.pageText, result),
            styleUsed = result.styleUsed.ifBlank { draft.mode.name },
        )

    private companion object {
        const val INFERENCE_TIMEOUT_MS = 60_000L

        /**
         * The whole snap's allowance in the model. Past this the rest is done locally.
         *
         * Chosen to be longer than a page needs and shorter than a person will wait: what
         * matters is that it is finite and shared, so no combination of rungs and runs can
         * add up to an answer that never comes.
         */
        const val SNAP_BUDGET_MS = 150_000L

        /** Said when a style that must be written was asked for with nothing to write it. */
        /** Said when a photo is the only source and nothing is installed to read it. */
        const val NEEDS_MODEL_TO_READ =
            "Turn on offline AI to read photos — it reads printed pages and handwriting."

        const val NEEDS_MODEL_FOR_STYLE =
            "Shortened on the spot. Turn on offline AI for bullets, steps and analogies."

        /** A page shorter than this has nothing worth condensing. */
        const val MIN_PROSE_SOURCE_CHARS = 180
        /** Characters per word including the space, for turning room into a word target. */
        const val AVERAGE_WORD_CHARS = 6.0

        /** Leaves the reduced text a little clear of the window rather than flush to it. */
        const val FIT_MARGIN = 0.9
        const val PROSE_RATIO = 0.34f

        /** Words on a typical book page, used when nothing was recognised to measure. */
        const val TYPICAL_PAGE_WORDS = 320
        const val DEFAULT_MAX_OUTPUT_TOKENS = 560

        /** Below this there is no retelling left, only a label. */
        const val ABSOLUTE_MIN_WORDS = 18

        /** Keep an imperfect retelling rather than show nothing. */
        const val MIN_KEEPABLE_PROSE_CHARS = 120
    }
}

sealed interface WorkflowEvent {
    data class Phase(val update: PhaseUpdate) : WorkflowEvent
    data class Token(val value: String) : WorkflowEvent
    data class Result(val result: KnowledgeResult, val fromModel: Boolean) : WorkflowEvent
}
