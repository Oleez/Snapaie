package com.snapaie.android.domain.scan

import android.content.Context
import com.snapaie.android.data.ai.ModelRepository
import com.snapaie.android.data.ai.ModelSessionManager
import com.snapaie.android.data.model.BookScanDraft
import com.snapaie.android.data.model.KnowledgeResult
import com.snapaie.android.data.model.ModelTier
import com.snapaie.android.data.model.PhaseUpdate
import com.snapaie.android.data.model.ScanPhase
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout

class WorkflowEngine(
    private val context: Context,
    private val sessionManager: ModelSessionManager,
    private val modelRepository: ModelRepository,
) {
    private val parser = StructuredOutputParser()
    private val prompts = PromptLibrary(context)

    fun run(draft: BookScanDraft, tier: ModelTier): Flow<WorkflowEvent> = flow {
        emit(WorkflowEvent.Phase(PhaseUpdate(ScanPhase.Capture, "Page text captured.", isComplete = true)))
        emit(WorkflowEvent.Phase(PhaseUpdate(ScanPhase.Ocr, "OCR text ready for compression.", isComplete = true)))

        if (!modelRepository.modelFile(tier).exists()) {
            emit(WorkflowEvent.Phase(PhaseUpdate(ScanPhase.Compression, "Instant offline draft — download the model for full AI.", isComplete = true)))
            emit(WorkflowEvent.Result(finalize(draft, parser.heuristicOnly(draft)), fromModel = false))
            return@flow
        }

        emit(WorkflowEvent.Phase(PhaseUpdate(ScanPhase.Compression, "Compressing meaning with on-device Gemma…")))

        var attempt = streamOnce(prompts.buildScanPrompt(draft), tier) { token ->
            emit(WorkflowEvent.Token(token))
        }

        emit(WorkflowEvent.Phase(PhaseUpdate(ScanPhase.Compression, "Compression complete.", isComplete = true)))
        emit(WorkflowEvent.Phase(PhaseUpdate(ScanPhase.ClarityCheck, "Structuring the result…")))

        var outcome = parser.parse(attempt.text)
        if (outcome is ParseOutcome.Unparseable && attempt.text.isNotBlank()) {
            // Repair pass: one retry with a stricter JSON-only prompt.
            emit(WorkflowEvent.Phase(PhaseUpdate(ScanPhase.ClarityCheck, "Tightening the output format…")))
            val retry = streamOnce(prompts.buildRepairPrompt(draft, attempt.text), tier) { }
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
        emit(WorkflowEvent.Result(finalize(draft, result), fromModel = true))
    }

    private data class StreamAttempt(val text: String, val timeoutReason: String?)

    /** Collects one full model stream (with timeout), forwarding tokens to [onToken]. */
    private suspend fun streamOnce(
        prompt: String,
        tier: ModelTier,
        onToken: suspend (String) -> Unit,
    ): StreamAttempt {
        val accumulated = StringBuilder()
        var timeoutReason: String? = null
        try {
            withTimeout(INFERENCE_TIMEOUT_MS) {
                sessionManager.stream(prompt, tier).collect { token ->
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
    }
}

sealed interface WorkflowEvent {
    data class Phase(val update: PhaseUpdate) : WorkflowEvent
    data class Token(val value: String) : WorkflowEvent
    data class Result(val result: KnowledgeResult, val fromModel: Boolean) : WorkflowEvent
}
