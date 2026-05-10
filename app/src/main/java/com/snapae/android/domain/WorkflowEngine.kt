package com.snapae.android.domain

import android.content.Context
import com.snapae.android.data.ai.LocalInferenceEngine
import com.snapae.android.data.model.BookScanDraft
import com.snapae.android.data.model.FillerItem
import com.snapae.android.data.model.KnowledgeMode
import com.snapae.android.data.model.KnowledgeResult
import com.snapae.android.data.model.ModelTier
import com.snapae.android.data.model.PhaseUpdate
import com.snapae.android.data.model.ScanPhase
import com.snapae.android.data.model.VocabularyItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class WorkflowEngine(
    private val context: Context,
    private val inferenceEngine: LocalInferenceEngine,
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

        inferenceEngine.stream(prompt, tier).collect { token ->
            if (token.isNotBlank()) emit(WorkflowEvent.Token(token))
        }

        phases.drop(3).forEach { (phase, text) ->
            emit(WorkflowEvent.Phase(PhaseUpdate(phase, text, isComplete = true)))
            delay(90)
        }

        val generated = LocalKnowledgeDrafting.generate(draft)
        emit(WorkflowEvent.Result(parser.parseOrRepair(generated)))
    }

    fun cancel() {
        inferenceEngine.cancel()
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

    fun parseOrRepair(raw: String): KnowledgeResult {
        val trimmed = raw.substringAfter("```json", raw).substringBeforeLast("```").trim()
        return runCatching { json.decodeFromString<KnowledgeResult>(trimmed) }
            .getOrElse { LocalKnowledgeDrafting.emptyWithError("Local JSON repair needed: ${it.message}") }
    }
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
