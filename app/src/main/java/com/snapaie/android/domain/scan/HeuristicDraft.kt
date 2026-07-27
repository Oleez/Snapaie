package com.snapaie.android.domain.scan

import com.snapaie.android.data.model.BookScanDraft
import com.snapaie.android.data.model.ExplainStyle
import com.snapaie.android.data.model.FillerItem
import com.snapaie.android.data.model.KnowledgeResult
import com.snapaie.android.data.model.VocabularyItem

/**
 * Model-missing path only: an instant offline draft so first-run users see value
 * before the model download. The UI labels this clearly as a non-AI draft.
 */
object HeuristicDraft {

    fun generate(draft: BookScanDraft): KnowledgeResult {
        val sentences = draft.pageText
            .split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.length > 24 }
        val sourceName = draft.bookTitle.ifBlank { "this page" }
        val core = sentences.firstOrNull()
            ?: "This page is trying to communicate one useful idea, but the OCR text is thin."
        return KnowledgeResult(
            conciseMeaning = when (draft.mode) {
                ExplainStyle.Concise, ExplainStyle.Bullets -> core.take(220)
                ExplainStyle.Steps -> "In simple terms: ${core.take(200)}"
                ExplainStyle.Auto -> "The main point is: ${core.take(200)}"
                ExplainStyle.Analogy -> "Think of it like this: ${core.take(200)}"
                ExplainStyle.Detailed -> "Under the surface, the page is pointing at this: ${core.take(190)}"
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
            fillerDetected = detectFiller(sentences),
            compressionScore = compressionScore(draft.pageText, core, draft.mode),
            estimatedTimeSavedMinutes = ScanMetrics.minutesSaved(draft.pageText, core),
            hiddenMeaning = "The hidden value is not the wording; it is the mental model the author is trying to install.",
            keyQuotesToKeep = sentences.take(2),
            styleUsed = draft.mode.name,
        )
    }

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

    private fun compressionScore(text: String, core: String, mode: ExplainStyle): Int {
        val base = 100 - ((core.length.toFloat() / text.length.coerceAtLeast(1)) * 100).toInt()
        val modeBoost = if (mode == ExplainStyle.Concise || mode == ExplainStyle.Bullets) 10 else 0
        return (base + modeBoost).coerceIn(35, 92)
    }

    private fun vocabularyFrom(text: String): List<VocabularyItem> =
        Regex("\\b[A-Za-z]{10,}\\b")
            .findAll(text)
            .map { it.value.lowercase() }
            .distinct()
            .take(4)
            .map {
                VocabularyItem(
                    word = it,
                    meaning = "A complex term from the scanned text.",
                    simplerVersion = "Use the surrounding sentence to restate this in plain language.",
                )
            }
            .toList()
}

/** Honest, locally computed reading metrics (never model-invented). */
object ScanMetrics {
    private const val WORDS_PER_MINUTE = 238

    fun wordCount(text: String): Int =
        text.split(Regex("\\s+")).count { it.isNotBlank() }

    /** Reading-time delta between the source text and the compressed output, clamped >= 0. */
    fun minutesSaved(sourceText: String, outputText: String): Int {
        val inMinutes = wordCount(sourceText).toDouble() / WORDS_PER_MINUTE
        val outMinutes = wordCount(outputText).toDouble() / WORDS_PER_MINUTE
        return (inMinutes - outMinutes).toInt().coerceAtLeast(0)
    }

    fun minutesSavedForResult(sourceText: String, result: KnowledgeResult): Int {
        val output = buildString {
            append(result.conciseMeaning).append(' ')
            append(result.coreIdea).append(' ')
            append(result.simplifiedExplanation).append(' ')
            result.actionableInsights.forEach { append(it).append(' ') }
            append(result.plainTextFallback)
        }
        return minutesSaved(sourceText, output)
    }
}
