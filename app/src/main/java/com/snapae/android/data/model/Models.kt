package com.snapae.android.data.model

import kotlinx.serialization.Serializable

enum class AssetType(val label: String) {
    VideoNotes("Video notes"),
    Transcript("Transcript"),
    ProductPitch("Product pitch"),
    Feature("Feature"),
    Screenshot("Screenshot"),
    Idea("Idea"),
    Update("Update"),
}

enum class WorkflowPhase(val label: String) {
    Intake("Intake strategist"),
    Audience("Audience analyst"),
    Hook("Hook angle"),
    Titles("Title lab"),
    Captions("Caption writer"),
    Repurpose("Repurposing planner"),
    Schedule("Schedule strategist"),
    Analytics("Analytics analyst"),
    Ideas("Next ideas"),
    Guardrails("Quality guardrails"),
}

@Serializable
data class LaunchPackage(
    val audience: String = "",
    val strongestHookAngle: String = "",
    val titles: List<String> = emptyList(),
    val hooks: List<String> = emptyList(),
    val captions: List<String> = emptyList(),
    val hashtags: List<String> = emptyList(),
    val thumbnailText: List<String> = emptyList(),
    val pinnedComment: String = "",
    val cta: String = "",
    val repurposingBlocks: List<String> = emptyList(),
    val scheduleSuggestion: String = "",
    val engagementPrompts: List<String> = emptyList(),
    val analyticsTemplateMarkdown: String = "",
    val nextIdeas: List<String> = emptyList(),
) {
    fun toMarkdown(): String = buildString {
        appendLine("# SnapAE Launch Package")
        appendLine()
        appendLine("## Audience")
        appendLine(audience.ifBlank { "Not generated." })
        appendLine()
        appendLine("## Strongest Hook Angle")
        appendLine(strongestHookAngle.ifBlank { "Not generated." })
        appendLine()
        appendList("Titles", titles)
        appendList("Hooks", hooks)
        appendList("Captions", captions)
        appendList("Hashtags", hashtags)
        appendList("Thumbnail Text", thumbnailText)
        appendLine("## Pinned Comment")
        appendLine(pinnedComment.ifBlank { "Not generated." })
        appendLine()
        appendLine("## CTA")
        appendLine(cta.ifBlank { "Not generated." })
        appendLine()
        appendList("Repurposing Blocks", repurposingBlocks)
        appendLine("## Schedule Suggestion")
        appendLine(scheduleSuggestion.ifBlank { "Not generated." })
        appendLine()
        appendList("Engagement Prompts", engagementPrompts)
        appendLine("## Analytics Template")
        appendLine(analyticsTemplateMarkdown.ifBlank { "Not generated." })
        appendLine()
        appendList("Next Ideas", nextIdeas)
    }
}

private fun StringBuilder.appendList(title: String, values: List<String>) {
    appendLine("## $title")
    if (values.isEmpty()) {
        appendLine("Not generated.")
    } else {
        values.forEachIndexed { index, value -> appendLine("${index + 1}. $value") }
    }
    appendLine()
}

data class InputDraft(
    val assetType: AssetType = AssetType.Transcript,
    val title: String = "",
    val content: String = "",
    val context: String = "",
)

data class PhaseUpdate(
    val phase: WorkflowPhase,
    val text: String,
    val isComplete: Boolean = false,
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
        displayName = "Gemma 4 E2B quantized",
        fileName = "gemma-4-E2B-it.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        sha256 = "",
        estimatedBytes = 7_200_000_000L,
        recommendedRamGb = 6,
    ),
    Gemma4E4B(
        displayName = "Gemma 4 E4B quantized",
        fileName = "gemma-4-E4B-it.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
        sha256 = "",
        estimatedBytes = 9_600_000_000L,
        recommendedRamGb = 10,
    ),
}
