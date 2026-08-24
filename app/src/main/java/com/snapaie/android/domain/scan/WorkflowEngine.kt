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

        // Give it only the room the target needs, with headroom for the ledger block.
        val attempt = streamOnce(prompt, maxOutputTokens = budget * 2 + 160, onToken = onToken)
        val prose = BeatContract.split(attempt.text).prose
        return if (BeatContract.evaluate(prose, source, budget) == BeatRejection.NONE) prose else ""
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
    }
}

sealed interface WorkflowEvent {
    data class Phase(val update: PhaseUpdate) : WorkflowEvent
    data class Token(val value: String) : WorkflowEvent
    data class Result(val result: KnowledgeResult, val fromModel: Boolean) : WorkflowEvent
}
