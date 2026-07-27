package com.snapaie.android.domain.scan

import com.snapaie.android.data.model.BookScanDraft
import com.snapaie.android.data.model.KnowledgeResult
import kotlinx.serialization.json.Json

sealed interface ParseOutcome {
    /** Structured result decoded successfully. */
    data class Structured(val result: KnowledgeResult) : ParseOutcome

    /** Nothing parseable — caller may retry once with a stricter prompt. */
    data object Unparseable : ParseOutcome
}

class StructuredOutputParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun heuristicOnly(draft: BookScanDraft): KnowledgeResult = HeuristicDraft.generate(draft)

    /** Runs the repair ladder over [raw]; never throws. */
    fun parse(raw: String?): ParseOutcome {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isBlank()) return ParseOutcome.Unparseable
        for (candidate in JsonRepair.candidates(trimmed)) {
            val decoded = runCatching { json.decodeFromString<KnowledgeResult>(candidate) }.getOrNull()
            if (decoded != null && hasUsefulSignal(decoded)) {
                return ParseOutcome.Structured(decoded)
            }
        }
        return ParseOutcome.Unparseable
    }

    /**
     * Final fallback per the output contract: render the model's prose as-is
     * rather than showing an error, or fall back to the offline heuristic draft
     * when the model produced nothing readable.
     */
    fun plainTextOrHeuristic(draft: BookScanDraft, raw: String?, reason: String?): KnowledgeResult {
        val trimmed = raw?.trim().orEmpty()
        val looksLikeProse = trimmed.length >= MIN_PROSE_CHARS &&
            !trimmed.contains("LiteRT-LM stream error", ignoreCase = true)
        if (looksLikeProse) {
            return KnowledgeResult(plainTextFallback = JsonRepair.stripFences(trimmed))
        }
        val prefix = reason ?: when {
            trimmed.contains("LiteRT-LM stream error", ignoreCase = true) ->
                "The local model reported an error. Showing structured offline summary instead."
            trimmed.isBlank() ->
                "No response from the local model. Showing structured offline summary instead."
            else ->
                "Could not read the model output. Showing structured offline summary instead."
        }
        val heuristic = heuristicOnly(draft)
        return heuristic.copy(conciseMeaning = "$prefix ${heuristic.conciseMeaning}".trim())
    }

    private fun hasUsefulSignal(result: KnowledgeResult): Boolean =
        result.conciseMeaning.isNotBlank() ||
            result.coreIdea.isNotBlank() ||
            result.simplifiedExplanation.isNotBlank()

    private companion object {
        const val MIN_PROSE_CHARS = 80
    }
}
