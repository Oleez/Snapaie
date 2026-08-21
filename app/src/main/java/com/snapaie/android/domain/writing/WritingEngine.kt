package com.snapaie.android.domain.writing

import com.snapaie.android.data.ai.ModelSessionManager
import com.snapaie.android.domain.chat.Languages
import com.snapaie.android.domain.scan.JsonRepair
import kotlinx.coroutines.flow.Flow

enum class WritingTool(val id: String, val label: String, val emoji: String) {
    Fix("fix", "Fix grammar", "✅"),
    Rewrite("rewrite", "Rewrite", "♻️"),
    Tone("tone", "Change tone", "🎭"),
    Shorten("shorten", "Shorten", "✂️"),
    Expand("expand", "Expand", "➕"),
    Paraphrase("paraphrase", "Paraphrase", "🔁"),
    Humanize("humanize", "Humanize", "🧑"),
    Summarize("summarize", "Summarize", "📌"),
    Translate("translate", "Translate", "🌐"),
    Synonyms("synonyms", "Synonyms", "📚"),
}

enum class WritingStyle(val id: String, val label: String) {
    Standard("standard", "Standard"),
    Fluent("fluent", "Fluent"),
    Formal("formal", "Formal"),
    Simple("simple", "Simple"),
    Creative("creative", "Creative"),
}

enum class HumanizeStrength(val id: String, val label: String) {
    Subtle("subtle", "Subtle"),
    Balanced("balanced", "Balanced"),
    Strong("strong", "Strong"),
}

enum class WritingDialect(val id: String, val label: String) {
    American("american", "American"),
    British("british", "British"),
    Australian("australian", "Australian"),
    Canadian("canadian", "Canadian"),
    Indian("indian", "Indian"),
}

data class WritingRequest(
    val tool: WritingTool = WritingTool.Rewrite,
    val text: String = "",
    val style: WritingStyle = WritingStyle.Standard,
    val tone: String = "friendly",
    val format: String = "paragraph", // paragraph | bullets
    val length: String = "medium", // short | medium | long
    val dialect: WritingDialect = WritingDialect.American,
    val humanizeStrength: HumanizeStrength = HumanizeStrength.Balanced,
    val targetLanguage: String = "en",
    val variantNonce: Int = 0,
)

class WritingEngine(private val sessionManager: ModelSessionManager) {

    fun run(request: WritingRequest): Flow<String> =
        sessionManager.stream(buildPrompt(request))

    fun cleanOutput(raw: String): String {
        var s = JsonRepair.stripFences(raw).trim()
        if (s.length > 1 && ((s.startsWith('"') && s.endsWith('"')) || (s.startsWith('“') && s.endsWith('”')))) {
            s = s.substring(1, s.length - 1).trim()
        }
        return s
    }

    private fun buildPrompt(request: WritingRequest): String {
        val instruction = when (request.tool) {
            WritingTool.Fix -> "Correct all grammar, spelling, and punctuation mistakes in the text. Preserve the original meaning, tone, and formatting. Change as little as possible."
            WritingTool.Rewrite -> "Rewrite the text in a ${request.style.label.lowercase()} style so it reads clearly and naturally while keeping the exact meaning."
            WritingTool.Tone -> "Rewrite the text so the tone becomes ${request.tone}. Keep the meaning and information identical."
            WritingTool.Shorten -> "Shorten the text substantially while keeping every essential point. Aim for roughly half the length or less."
            WritingTool.Expand -> "Expand the text with more depth, supporting detail, and smoother flow, staying faithful to the original meaning."
            WritingTool.Paraphrase -> "Paraphrase the text with different wording and sentence structure, ${request.style.label.lowercase()} style, keeping the meaning identical."
            WritingTool.Humanize -> humanizeInstruction(request.humanizeStrength)
            WritingTool.Summarize -> "Summarize the text, keeping only the essential points."
            WritingTool.Translate -> "Translate the text into ${Languages.nameFor(request.targetLanguage)}. Preserve tone, register, and formatting."
            WritingTool.Synonyms -> "List 6-10 strong synonyms or alternative phrasings for the given word or phrase, ordered from closest to loosest match, each with a very short usage note."
        }
        val formatRule = when {
            request.tool == WritingTool.Synonyms -> "Format as a simple dashed list."
            request.format == "bullets" -> "Format the result as concise bullet points."
            else -> "Format the result as flowing paragraphs."
        }
        val lengthRule = when (request.length) {
            "short" -> "Keep the result short."
            "long" -> "A longer, fuller result is acceptable."
            else -> "Keep the result about the same length as the input unless the tool requires otherwise."
        }
        val variantRule = if (request.variantNonce > 0) {
            "Produce a noticeably different variation than an earlier attempt (variation #${request.variantNonce + 1}). Do not repeat previous phrasing."
        } else {
            ""
        }
        return buildString {
            appendLine("You are a precise writing assistant. $instruction")
            appendLine("Use ${request.dialect.label} English spelling and conventions when writing English.")
            appendLine(formatRule)
            appendLine(lengthRule)
            if (variantRule.isNotBlank()) appendLine(variantRule)
            appendLine("Output ONLY the transformed text. No preamble, no explanations, no quotes around the result, no markdown fences.")
            appendLine()
            appendLine("TEXT:")
            append(request.text.take(6000))
        }
    }

    private fun humanizeInstruction(strength: HumanizeStrength): String = when (strength) {
        HumanizeStrength.Subtle -> "Lightly humanize the text: vary sentence rhythm and soften robotic phrasing while keeping wording mostly intact."
        HumanizeStrength.Balanced -> "Humanize the text so it reads like a thoughtful person wrote it: natural rhythm, contractions where fitting, no stiff AI patterns, meaning unchanged."
        HumanizeStrength.Strong -> "Aggressively humanize the text: rework phrasing into a natural, conversational human voice with varied sentence lengths and zero formulaic AI patterns, while preserving all facts and intent."
    }
}
