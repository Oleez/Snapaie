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

        var attempt = streamOnce(prompts.buildScanPrompt(draft)) { token ->
            emit(WorkflowEvent.Token(token))
        }

        emit(WorkflowEvent.Phase(PhaseUpdate(ScanPhase.Compression, "Compression complete.", isComplete = true)))
        emit(WorkflowEvent.Phase(PhaseUpdate(ScanPhase.ClarityCheck, "Structuring the result…")))

        var outcome = parser.parse(attempt.text)
        if (outcome is ParseOutcome.Unparseable && attempt.text.isNotBlank()) {
            // Repair pass: one retry with a stricter JSON-only prompt.
            emit(WorkflowEvent.Phase(PhaseUpdate(ScanPhase.ClarityCheck, "Tightening the output format…")))
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
        emit(WorkflowEvent.Phase(PhaseUpdate(ScanPhase.ClarityCheck, "Clarity check complete.", isComplete = true)))

        // The part people actually read: the page retold shorter, in order, as prose. The
        // structured fields above dissect the page, which is a different thing from a
        // shorter version of it — a list of findings never reads like the text did.
        emit(WorkflowEvent.Phase(PhaseUpdate(ScanPhase.Compression, "Rewriting it shorter…")))
        val prose = condenseToProse(draft) { token -> emit(WorkflowEvent.Token(token)) }
        emit(WorkflowEvent.Phase(PhaseUpdate(ScanPhase.Compression, "Shorter version ready.", isComplete = true)))

        emit(WorkflowEvent.Result(finalize(draft, result.copy(condensedProse = prose)), fromModel = true))
    }


    /**
     * Retells the page at roughly a third of its length, using the same contract the book
     * pipeline uses so a page and a chapter read the same way.
     *
     * Failure here is not worth losing the rest of the result over: the structured fields
     * are already in hand, so a blank prose section is a smaller loss than an error screen.
     */
    private suspend fun condenseToProse(
        draft: BookScanDraft,
        onToken: suspend (String) -> Unit,
    ): String {
        val source = draft.pageText.trim()
        if (source.length < MIN_PROSE_SOURCE_CHARS) return ""

        val budget = (Segmenter.countWords(source) * PROSE_RATIO).toInt()
            .coerceAtLeast(BudgetGovernor.MIN_BEAT_WORDS)
        val template = runCatching { prompts.readAsset("prompts/condense.md") }.getOrDefault("")
        if (template.isBlank()) return ""

        val prompt = template
            .replace("{{TARGET_WORDS}}", budget.toString())
            .replace("{{DELIMITER}}", BeatContract.LEDGER_DELIMITER)
            .replace("{{LEDGER}}", "This is a single page on its own. Nothing came before it.")
            .replace("{{PREVIOUS_TAIL}}", "(nothing yet)")
            .replace("{{SOURCE}}", source.take(MAX_PROSE_SOURCE_CHARS))

        val attempt = streamOnce(prompt, onToken)
        val prose = BeatContract.split(attempt.text).prose
        // Reject a summary-shaped answer rather than presenting it as a retelling.
        return if (BeatContract.evaluate(prose, source, budget) == BeatRejection.NONE) prose else ""
    }

    private data class StreamAttempt(val text: String, val timeoutReason: String?)

    /** Collects one full model stream (with timeout), forwarding tokens to [onToken]. */
    private suspend fun streamOnce(
        prompt: String,
        onToken: suspend (String) -> Unit,
    ): StreamAttempt {
        val accumulated = StringBuilder()
        var timeoutReason: String? = null
        try {
            withTimeout(INFERENCE_TIMEOUT_MS) {
                sessionManager.stream(prompt).collect { token ->
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
    }
}

sealed interface WorkflowEvent {
    data class Phase(val update: PhaseUpdate) : WorkflowEvent
    data class Token(val value: String) : WorkflowEvent
    data class Result(val result: KnowledgeResult, val fromModel: Boolean) : WorkflowEvent
}
