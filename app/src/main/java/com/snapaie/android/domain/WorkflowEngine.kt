package com.snapaie.android.domain

import android.content.Context
import com.snapaie.android.data.ai.LocalInferenceEngine
import com.snapaie.android.data.model.BookScanDraft
import com.snapaie.android.data.model.FillerItem
import com.snapaie.android.data.model.KnowledgeMode
import com.snapaie.android.data.model.KnowledgeResult
import com.snapaie.android.data.model.ModelTier
import com.snapaie.android.data.model.PhaseUpdate
import com.snapaie.android.data.model.ScanPhase
import com.snapaie.android.data.model.VocabularyItem
import com.snapaie.android.data.ai.ModelRepository
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class WorkflowEngine(
    private val context: Context,
    private val inferenceEngine: LocalInferenceEngine,
    private val modelRepository: ModelRepository,
) {
    private val parser = StructuredOutputParser()

    fun run(draft: BookScanDraft, tier: ModelTier): Flow<WorkflowEvent> = flow {
        val prompt = PromptLibrary(context).buildPrompt(draft)
        val phases = listOf(
            ScanPhase.Capture to "Page text captured. Preparing signal extraction.",
            ScanPhase.Ocr to "OCR text is ready for compression.",
            ScanPhase.FillerScan to "Detecting repetition, padding, and unnecessary complexity.",
            ScanPhase.Compression to "Compressing meaning into high-signal form.",
            ScanPhase.Insight to "Extracting what the author is really trying to say.",
            ScanPhase.Vocabulary to "Pulling difficult terms into simple language.",
            ScanPhase.Takeaways to "Converting ideas into practical takeaways.",
            ScanPhase.ClarityCheck to "Removing essay-like output and keeping it sharp.",
        )

        phases.take(3).forEach { (phase, text) ->
            emit(WorkflowEvent.Phase(PhaseUpdate(phase, text)))
            delay(120)
        }

        val modelMissing = !modelRepository.modelFile(tier).exists()
        if (modelMissing) {
            phases.drop(3).forEach { (phase, text) ->
                emit(WorkflowEvent.Phase(PhaseUpdate(phase, text, isComplete = true)))
                delay(90)
            }
            emit(WorkflowEvent.Result(parser.heuristicOnly(draft)))
            return@flow
        }

        val accumulated = StringBuilder()
        var inferenceTimedOut = false
        try {
            withTimeout(InferenceTimeoutMs) {
                inferenceEngine.stream(prompt, tier).collect { token ->
                    accumulated.append(token)
                    if (token.isNotBlank()) emit(WorkflowEvent.Token(token))
                }
            }
        } catch (_: TimeoutCancellationException) {
            inferenceEngine.cancel()
            inferenceTimedOut = true
        }

        phases.drop(3).forEach { (phase, text) ->
            emit(WorkflowEvent.Phase(PhaseUpdate(phase, text, isComplete = true)))
            delay(90)
        }

        val raw = accumulated.toString()
        val fallbackReason = when {
            inferenceTimedOut -> "Local model stopped after ${InferenceTimeoutMs / 1000}s. Showing structured offline summary instead."
            else -> null
        }
        emit(
            WorkflowEvent.Result(
                parser.parseStructuredOrFallback(
                    draft = draft,
                    fallbackReason = fallbackReason,
                    rawModelOutput = raw,
                ),
            ),
        )
    }

    fun cancel() {
        inferenceEngine.cancel()
    }

    companion object {
        private const val InferenceTimeoutMs = 240_000L
    }
}

sealed interface WorkflowEvent {
    data class Phase(val update: PhaseUpdate) : WorkflowEvent
    data class Token(val value: String) : WorkflowEvent
    data class Result(val result: KnowledgeResult) : WorkflowEvent
}

class PromptLibrary(private val context: Context) {
    fun buildPrompt(draft: BookScanDraft): String {
        val guardrails = readAsset("prompts/guardrails.md")
        val orchestrator = readAsset("prompts/orchestrator.md")
        return """
            $guardrails

            $orchestrator

            Mode: ${draft.mode.label}
            Mode behavior: ${draft.mode.description}
            Book/source: ${draft.bookTitle}
            User context: ${draft.context}
            OCR/page text:
            ${draft.pageText}
        """.trimIndent()
    }

    private fun readAsset(path: String): String = context.assets.open(path).bufferedReader().use { it.readText() }
}

class StructuredOutputParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun heuristicOnly(draft: BookScanDraft): KnowledgeResult =
        decodeHeuristic(LocalKnowledgeDrafting.generate(draft))

    fun parseStructuredOrFallback(
        draft: BookScanDraft,
        fallbackReason: String?,
        rawModelOutput: String?,
    ): KnowledgeResult {
        val trimmed = rawModelOutput?.trim().orEmpty()
        val decoded = tryParse(trimmed).takeIf { hasUsefulSignal(it) }

        if (decoded != null) {
            return if (fallbackReason != null) {
                decoded.copy(conciseMeaning = "$fallbackReason ${decoded.conciseMeaning}".trim())
            } else {
                decoded
            }
        }

        val prefix = fallbackReason ?: when {
            trimmed.contains("LiteRT-LM stream error", ignoreCase = true) ->
                "The local model reported an error. Showing structured offline summary instead."
            trimmed.isBlank() ->
                "No response from local model yet. Showing structured offline summary instead."
            else ->
                "Could not parse the model output as JSON. Showing structured offline summary instead."
        }

        val heuristic = heuristicOnly(draft)
        return heuristic.copy(
            conciseMeaning = "$prefix ${heuristic.conciseMeaning}".trim(),
        )
    }

    private fun decodeHeuristic(jsonString: String): KnowledgeResult =
        runCatching { json.decodeFromString<KnowledgeResult>(jsonString) }
            .getOrElse { KnowledgeResult(conciseMeaning = "Offline summary unavailable: ${it.message}") }

    private fun tryParse(raw: String): KnowledgeResult? {
        val candidate = extractCandidateJson(raw) ?: return null
        return runCatching { json.decodeFromString<KnowledgeResult>(candidate) }.getOrNull()
    }

    /** Best-effort: fenced ```json blocks, then first balanced `{ ... }`. */
    private fun extractCandidateJson(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        val fenceIdx = trimmed.indexOf("```json")
        if (fenceIdx >= 0) {
            val afterOpen = trimmed.indexOf('\n', fenceIdx).takeIf { it >= 0 }?.plus(1) ?: fenceIdx + 7
            val close = trimmed.lastIndexOf("```")
            if (close > afterOpen) {
                val inner = trimmed.substring(afterOpen, close).trim()
                substringBalancedJson(inner)?.let { return it }
            }
        }

        return substringBalancedJson(trimmed)
    }

    private fun substringBalancedJson(s: String): String? {
        val start = s.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until s.length) {
            val c = s[i]
            when {
                escape -> escape = false
                c == '\\' && inString -> escape = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return s.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private fun hasUsefulSignal(result: KnowledgeResult): Boolean =
        result.conciseMeaning.isNotBlank() ||
            result.coreIdea.isNotBlank() ||
            result.simplifiedExplanation.isNotBlank()
}

private object LocalKnowledgeDrafting {
    private val json = Json { encodeDefaults = true }

    fun generate(draft: BookScanDraft): String {
        val sentences = draft.pageText
            .split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.length > 24 }
        val sourceName = draft.bookTitle.ifBlank { "this page" }
        val core = sentences.firstOrNull()
            ?: "This page is trying to communicate one useful idea, but the OCR text is thin."
        val filler = detectFiller(sentences)
        val score = compressionScore(draft.pageText, core, draft.mode)
        val result = KnowledgeResult(
            conciseMeaning = when (draft.mode) {
                KnowledgeMode.Concise, KnowledgeMode.FastRead -> core.take(220)
                KnowledgeMode.Student -> "In simple terms: ${core.take(200)}"
                KnowledgeMode.CoreInsight -> "The main point is: ${core.take(200)}"
                KnowledgeMode.DeepMeaning -> "Under the surface, the page is pointing at this: ${core.take(190)}"
            },
            coreIdea = "The author is using $sourceName to move the reader toward this idea: ${core.take(180)}",
            authorIntent = "The author wants the reader to accept the central claim without getting lost in repeated setup or decorative explanation.",
            simplifiedExplanation = "Strip it down: remember the main claim, the reason it matters, and one way to use it. Ignore repeated framing unless it adds new proof.",
            actionableInsights = listOf(
                "Write the core idea in one sentence before reading the next page.",
                "Mark only examples that prove the main point.",
                "Skip paragraphs that restate the same claim without new evidence.",
            ),
            importantVocabulary = vocabularyFrom(draft.pageText),
            fillerDetected = filler,
            compressionScore = score,
            estimatedTimeSavedMinutes = (draft.pageText.length / 900).coerceAtLeast(1),
            hiddenMeaning = "The hidden value is not the wording; it is the mental model the author is trying to install.",
            keyQuotesToKeep = sentences.take(2),
        )
        return json.encodeToString(result)
    }

    fun emptyWithError(message: String): KnowledgeResult = KnowledgeResult(conciseMeaning = message)

    private fun detectFiller(sentences: List<String>): List<FillerItem> = sentences
        .filter { sentence ->
            sentence.contains("in other words", ignoreCase = true) ||
                sentence.contains("it is important to note", ignoreCase = true) ||
                sentence.length > 220
        }
        .take(4)
        .map {
            FillerItem(
                excerpt = it.take(140),
                reason = "Likely restatement, setup, or overlong explanation.",
                type = "Low-signal text",
            )
        }

    private fun compressionScore(text: String, core: String, mode: KnowledgeMode): Int {
        val base = 100 - ((core.length.toFloat() / text.length.coerceAtLeast(1)) * 100).toInt()
        val modeBoost = if (mode == KnowledgeMode.Concise || mode == KnowledgeMode.FastRead) 10 else 0
        return (base + modeBoost).coerceIn(35, 92)
    }

    private fun vocabularyFrom(text: String): List<VocabularyItem> {
        val candidates = Regex("\\b[A-Za-z]{10,}\\b")
            .findAll(text)
            .map { it.value.lowercase() }
            .distinct()
            .take(4)
            .toList()
        return candidates.map {
            VocabularyItem(
                word = it,
                meaning = "A complex term from the scanned text.",
                simplerVersion = "Use the surrounding sentence to restate this in plain language.",
            )
        }
    }
}
