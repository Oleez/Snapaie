package com.snapaie.android.data.model

import com.snapaie.android.BuildConfig
import kotlinx.serialization.Serializable

/**
 * Unified explanation style axis (merges the legacy 5 KnowledgeModes with the
 * extension's 6 explanation styles). Legacy DB rows map via [fromStored].
 */
enum class ExplainStyle(val label: String, val description: String) {
    Auto("Auto", "Pick the single best explanation style for this text automatically."),
    Concise("Concise", "Compress aggressively and keep only essential meaning."),
    Detailed("Detailed", "Explain thoroughly with depth, nuance, and hidden meaning."),
    Bullets("Bullets", "Summarize instantly as skimmable bullet points."),
    Analogy("Analogy", "Explain through a vivid, relatable analogy."),
    Steps("Steps", "Break it down step-by-step for exam-ready understanding."),
    ;

    companion object {
        /** Maps both new names and legacy KnowledgeMode names stored in Room. */
        fun fromStored(value: String): ExplainStyle = when (value) {
            "Concise" -> Concise
            "Detailed", "DeepMeaning" -> Detailed
            "Bullets", "FastRead" -> Bullets
            "Analogy" -> Analogy
            "Steps", "Student" -> Steps
            "Auto", "CoreInsight" -> Auto
            else -> Auto
        }
    }
}

enum class ScanPhase(val label: String) {
    Capture("Capture"),
    Ocr("OCR"),
    FillerScan("Filler scan"),
    Compression("Compression"),
    Insight("Core insight"),
    Vocabulary("Vocabulary"),
    Takeaways("Takeaways"),
    ClarityCheck("Clarity check"),
}

@Serializable
data class KnowledgeResult(
    val conciseMeaning: String = "",
    val coreIdea: String = "",
    val authorIntent: String = "",
    val simplifiedExplanation: String = "",
    val actionableInsights: List<String> = emptyList(),
    val importantVocabulary: List<VocabularyItem> = emptyList(),
    val fillerDetected: List<FillerItem> = emptyList(),
    val compressionScore: Int = 0,
    val estimatedTimeSavedMinutes: Int = 0,
    val hiddenMeaning: String = "",
    val keyQuotesToKeep: List<String> = emptyList(),
    val cefrVocabulary: CefrVocab? = null,
    val plainTextFallback: String = "",
    val styleUsed: String = "",
) {
    val isPlainTextOnly: Boolean
        get() = plainTextFallback.isNotBlank() &&
            conciseMeaning.isBlank() && coreIdea.isBlank() && simplifiedExplanation.isBlank()

    fun toMarkdown(includeBranding: Boolean = true): String = buildString {
        appendLine("# snapaie Knowledge Scan")
        appendLine()
        if (isPlainTextOnly) {
            appendLine(plainTextFallback)
        } else {
            appendLine("**Compression:** ${compressionScore.coerceIn(0, 100)}%")
            appendLine("**Estimated time saved:** ${estimatedTimeSavedMinutes.coerceAtLeast(0)} min")
            appendLine()
            appendLine("## Concise Meaning")
            appendLine(conciseMeaning.ifBlank { "Not generated." })
            appendLine()
            appendLine("## Core Idea")
            appendLine(coreIdea.ifBlank { "Not generated." })
            appendLine()
            appendLine("## Author Intent")
            appendLine(authorIntent.ifBlank { "Not generated." })
            appendLine()
            appendLine("## Simplified Explanation")
            appendLine(simplifiedExplanation.ifBlank { "Not generated." })
            appendLine()
            appendList("Actionable Insights", actionableInsights)
            appendLine("## Smart Vocabulary")
            if (importantVocabulary.isEmpty()) {
                appendLine("Not generated.")
            } else {
                importantVocabulary.forEach {
                    appendLine("- **${it.word}**: ${it.meaning} Simpler: ${it.simplerVersion}")
                }
            }
            appendLine()
            appendLine("## Filler Detected")
            if (fillerDetected.isEmpty()) {
                appendLine("No obvious filler detected.")
            } else {
                fillerDetected.forEach { appendLine("- ${it.type}: ${it.excerpt} -> ${it.reason}") }
            }
            appendLine()
            appendLine("## Hidden Meaning")
            appendLine(hiddenMeaning.ifBlank { "Not generated." })
            appendLine()
            appendList("Key Quotes To Keep", keyQuotesToKeep)
            cefrVocabulary?.let { cefr ->
                appendLine("## CEFR Vocabulary")
                listOf("B2" to cefr.b2, "C1" to cefr.c1, "C2" to cefr.c2).forEach { (level, words) ->
                    if (words.isNotEmpty()) {
                        appendLine("### $level")
                        words.forEach { appendLine("- **${it.word}** (${it.partOfSpeech}): ${it.definition} _e.g. ${it.example}_") }
                    }
                }
                appendLine()
            }
        }
        if (includeBranding) {
            appendLine()
            appendLine("---")
            appendLine("*Made with snapaie — compressed from your scanned page.*")
        }
    }
}

@Serializable
data class VocabularyItem(
    val word: String,
    val meaning: String,
    val simplerVersion: String,
    val pronunciation: String = "",
)

@Serializable
data class FillerItem(
    val excerpt: String,
    val reason: String,
    val type: String,
)

@Serializable
data class CefrVocab(
    val b2: List<CefrWord> = emptyList(),
    val c1: List<CefrWord> = emptyList(),
    val c2: List<CefrWord> = emptyList(),
) {
    val isEmpty: Boolean get() = b2.isEmpty() && c1.isEmpty() && c2.isEmpty()
}

@Serializable
data class CefrWord(
    val word: String,
    val partOfSpeech: String = "",
    val definition: String = "",
    val example: String = "",
)

private fun StringBuilder.appendList(title: String, values: List<String>) {
    appendLine("## $title")
    if (values.isEmpty()) {
        appendLine("Not generated.")
    } else {
        values.forEach { value -> appendLine("- $value") }
    }
    appendLine()
}

data class BookScanDraft(
    val mode: ExplainStyle = ExplainStyle.Auto,
    val bookTitle: String = "",
    val pageText: String = "",
    val context: String = "",
)

data class PhaseUpdate(
    val phase: ScanPhase,
    val text: String,
    val isComplete: Boolean = false,
)

data class ReaderStats(
    val streakDays: Int = 0,
    val pagesProcessed: Int = 0,
    val insightsLearned: Int = 0,
    val minutesSaved: Int = 0,
    val averageCompression: Int = 0,
    val wordsIn: Int = 0,
    val wordsOut: Int = 0,
)

data class ModelSetupState(
    val selectedTier: ModelTier = ModelTier.Gemma3nE2B,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = ModelTier.Gemma3nE2B.estimatedBytes,
    val isDownloading: Boolean = false,
    val isReady: Boolean = false,
    val warning: String? = null,
) {
    val progress: Float
        get() = if (totalBytes <= 0L) 0f else downloadedBytes.toFloat() / totalBytes.toFloat()
}

enum class ModelTier(
    val displayName: String,
    val fileName: String,
    val sha256: String,
    val estimatedBytes: Long,
    val recommendedRamGb: Int,
) {
    Gemma3nE2B(
        displayName = "Gemma 3n E2B (fast, ~3.1 GB)",
        fileName = "gemma-3n-E2B-it-int4.litertlm",
        sha256 = "",
        estimatedBytes = 3_100_000_000L,
        recommendedRamGb = 4,
    ),
    Gemma3nE4B(
        displayName = "Gemma 3n E4B (sharper, ~4.4 GB)",
        fileName = "gemma-3n-E4B-it-int4.litertlm",
        sha256 = "",
        estimatedBytes = 4_400_000_000L,
        recommendedRamGb = 6,
    ),
    ;

    /**
     * Weights are served from a self-hosted mirror (Gemma Terms are shown and accepted
     * in-app before download). Falls back to the license-gated Hugging Face repo path
     * when no mirror is configured (dev builds).
     */
    val downloadUrl: String
        get() {
            val base = BuildConfig.MODEL_MIRROR_BASE_URL.trimEnd('/')
            return if (base.isNotBlank()) {
                "$base/$fileName"
            } else {
                val repo = when (this) {
                    Gemma3nE2B -> "litert-community/Gemma-3n-E2B-it-litert-lm"
                    Gemma3nE4B -> "litert-community/Gemma-3n-E4B-it-litert-lm"
                }
                "https://huggingface.co/$repo/resolve/main/$fileName"
            }
        }
}

/** SHA-256 from [gradle.properties] overrides enum defaults when set (release integrity). */
fun ModelTier.effectiveSha256(): String = when (this) {
    ModelTier.Gemma3nE2B -> BuildConfig.EXPECTED_MODEL_SHA256_E2B.ifBlank { sha256 }
    ModelTier.Gemma3nE4B -> BuildConfig.EXPECTED_MODEL_SHA256_E4B.ifBlank { sha256 }
}
