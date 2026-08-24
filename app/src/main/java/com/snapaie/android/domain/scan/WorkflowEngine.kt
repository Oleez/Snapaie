package com.snapaie.android.domain.scan

import android.content.Context
import com.snapaie.android.data.ai.ModelSessionManager
import com.snapaie.android.data.model.BookScanDraft
import com.snapaie.android.data.model.KnowledgeResult
import com.snapaie.android.data.model.PhaseUpdate
import com.snapaie.android.data.model.ScanPhase
import com.snapaie.android.domain.book.Segmenter
import com.snapaie.android.domain.condense.BeatContract
import com.snapaie.android.domain.condense.BeatRejection
import com.snapaie.android.domain.condense.BudgetGovernor
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout

class WorkflowEngine(
    private val context: Context,
    private val sessionManager: ModelSessionManager,
) {
    private val parser = StructuredOutputParser()
    private val prompts = PromptLibrary(context)

    fun run(draft: BookScanDraft): Flow<WorkflowEvent> = flow {
        emit(WorkflowEvent.Phase(PhaseUpdate(ScanPhase.Capture, "Page text captured.", isComplete = true)))
        emit(WorkflowEvent.Phase(PhaseUpdate(ScanPhase.Ocr, "Page text ready.", isComplete = true)))

        if (!sessionManager.isModelInstalled()) {
            emit(WorkflowEvent.Phase(PhaseUpdate(ScanPhase.Compression, "Quick draft. Turn on offline AI for the full result.", isComplete = true)))
            emit(WorkflowEvent.Result(finalize(draft, parser.heuristicOnly(draft)), fromModel = false))
            return@flow
        }

        emit(WorkflowEvent.Phase(PhaseUpdate(ScanPhase.Compression, "Condensing the page…")))

        // One model call, not two.
        //
        // This used to ask for a structured breakdown and then, separately, for the page
        // retold as prose — doubling the wait for a screen whose top half is the prose. The
        // retelling is what people came for, so it is the call that runs. The breakdown is
        // still available, on demand, from the result screen.
        val prose = condenseToProse(draft) { token -> emit(WorkflowEvent.Token(token)) }

        emit(WorkflowEvent.Phase(PhaseUpdate(ScanPhase.Compression, "Shorter version ready.", isComplete = true)))

        val result = if (prose.isNotBlank()) {
            // The cheap, honest parts computed locally rather than asked for: no second
            // round trip, and nothing the model could invent.
            parser.heuristicOnly(draft).copy(condensedProse = prose)
        } else {
            parser.heuristicOnly(draft)
        }
        emit(WorkflowEvent.Result(finalize(draft, result), fromModel = prose.isNotBlank()))
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

        var attempt = streamOnce(prompts.buildScanPrompt(draft)) { token ->
            emit(WorkflowEvent.Token(token))
        }
        var outcome = parser.parse(attempt.text)
        if (outcome is ParseOutcome.Unparseable && attempt.text.isNotBlank()) {
            val retry = streamOnce(prompts.buildRepairPrompt(draft, attempt.text)) { }
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
    private suspend fun condenseToProse(
        draft: BookScanDraft,
        onToken: suspend (String) -> Unit,
    ): String {
        val source = draft.pageText.trim()
        val hasImage = draft.imagePath.isNotBlank() && java.io.File(draft.imagePath).isFile
        if (!hasImage && source.length < MIN_PROSE_SOURCE_CHARS) return ""

        // Budget from whatever we know the page's length to be. With no recognised text to
        // measure, a typical book page is the fallback.
        val sourceWords = if (source.isNotBlank()) Segmenter.countWords(source) else TYPICAL_PAGE_WORDS
        val budget = (sourceWords * PROSE_RATIO).toInt().coerceAtLeast(BudgetGovernor.MIN_BEAT_WORDS)

        val template = runCatching { prompts.readAsset("prompts/condense_page.md") }.getOrDefault("")
        if (template.isBlank()) return ""

        // One pass, not two. When there is a photograph the model reads it and retells it
        // in the same generation, which removes a whole round trip from every snap — and
        // two of them whenever the text recogniser struggled and used to hand off to the
        // model just to transcribe.
        val prompt = template
            .replace("{{TARGET_WORDS}}", budget.toString())
            .replace(
                "{{SOURCE_BLOCK}}",
                if (hasImage) {
                    "The page is the attached image. Read it, then retell it."
                } else {
                    "The page:" + System.lineSeparator() + source.take(MAX_PROSE_SOURCE_CHARS)
                },
            )

        val budgetTokens = budget * 2 + 80
        val attempt = if (hasImage) {
            streamVision(prompt, draft.imagePath, budgetTokens, onToken)
        } else {
            streamOnce(prompt, budgetTokens, onToken)
        }

        val prose = BeatContract.cleanProse(attempt.text)
        // With no recognised text there is nothing to check names against, so the length
        // and meta-framing rules still apply but the name check cannot.
        return if (BeatContract.evaluate(prose, source, budget) == BeatRejection.NONE) prose else ""
    }

    private suspend fun streamVision(
        prompt: String,
        imagePath: String,
        maxOutputTokens: Int,
        onToken: suspend (String) -> Unit,
    ): StreamAttempt {
        val accumulated = StringBuilder()
        var timeoutReason: String? = null
        try {
            withTimeout(INFERENCE_TIMEOUT_MS) {
                sessionManager.streamWithImage(prompt, imagePath, maxOutputTokens).collect { token ->
                    accumulated.append(token)
                    if (token.isNotBlank()) onToken(token)
                }
            }
        } catch (_: TimeoutCancellationException) {
            timeoutReason = "Stopped early."
        } catch (_: IllegalStateException) {
            // Engine unavailable; the caller falls back to the local draft.
        }
        return StreamAttempt(accumulated.toString(), timeoutReason)
    }

    private data class StreamAttempt(val text: String, val timeoutReason: String?)

    /** Collects one full model stream (with timeout), forwarding tokens to [onToken]. */
    private suspend fun streamOnce(
        prompt: String,
        maxOutputTokens: Int = ModelSessionManager.DEFAULT_MAX_OUTPUT_TOKENS,
        onToken: suspend (String) -> Unit,
    ): StreamAttempt {
        val accumulated = StringBuilder()
        var timeoutReason: String? = null
        try {
            withTimeout(INFERENCE_TIMEOUT_MS) {
                sessionManager.stream(prompt, maxOutputTokens).collect { token ->
                    accumulated.append(token)
                    if (token.isNotBlank()) onToken(token)
                }
            }
        } catch (_: TimeoutCancellationException) {
            timeoutReason =
                "Local model stopped after ${INFERENCE_TIMEOUT_MS / 1000}s. Showing what it produced."
        } catch (error: IllegalStateException) {
            accumulated.append("\nLiteRT-LM stream error: ${error.message}")
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
        const val INFERENCE_TIMEOUT_MS = 240_000L

        /** A page shorter than this has nothing worth condensing. */
        const val MIN_PROSE_SOURCE_CHARS = 400
        const val MAX_PROSE_SOURCE_CHARS = 9_000
        const val PROSE_RATIO = 0.34f

        /** Words on a typical book page, used when nothing was recognised to measure. */
        const val TYPICAL_PAGE_WORDS = 320
    }
}

sealed interface WorkflowEvent {
    data class Phase(val update: PhaseUpdate) : WorkflowEvent
    data class Token(val value: String) : WorkflowEvent
    data class Result(val result: KnowledgeResult, val fromModel: Boolean) : WorkflowEvent
}
