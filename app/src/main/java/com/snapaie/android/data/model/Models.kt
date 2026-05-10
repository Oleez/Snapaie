package com.snapaie.android.data.model

import kotlinx.serialization.Serializable

enum class KnowledgeMode(val label: String, val description: String) {
    Concise("Concise", "Compress aggressively and keep only essential meaning."),
    CoreInsight("Core Insight", "Extract the main takeaway and author intent."),
    Student("Student", "Simplify difficult concepts for exam-ready understanding."),
    FastRead("Fast Read", "Summarize the page instantly and reduce reading time."),
    DeepMeaning("Deep Meaning", "Explain hidden psychology, philosophy, or business insight."),
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
) {
    fun toMarkdown(): String = buildString {
        appendLine("# snapaie Knowledge Scan")
        appendLine()
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
                appendLine("- **${it.word}** (${it.pronunciation.ifBlank { "pronunciation TBD" }}): ${it.meaning} Simpler: ${it.simplerVersion}")
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
    val mode: KnowledgeMode = KnowledgeMode.Concise,
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
)

data class ModelSetupState(
    val selectedTier: ModelTier = ModelTier.Gemma4E2B,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = ModelTier.Gemma4E2B.estimatedBytes,
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
    val downloadUrl: String,
    val sha256: String,
    val estimatedBytes: Long,
    val recommendedRamGb: Int,
) {
    Gemma4E2B(
        displayName = "Gemma 4 E2B local",
        fileName = "gemma-4-E2B-it.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        sha256 = "",
        estimatedBytes = 7_200_000_000L,
        recommendedRamGb = 6,
    ),
    Gemma4E4B(
        displayName = "Gemma 4 E4B local",
        fileName = "gemma-4-E4B-it.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
        sha256 = "",
        estimatedBytes = 9_600_000_000L,
        recommendedRamGb = 10,
    ),
}
