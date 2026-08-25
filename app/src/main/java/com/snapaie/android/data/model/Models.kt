package com.snapaie.android.data.model

import kotlinx.serialization.Serializable

/**
 * Unified explanation style axis (merges the legacy 5 KnowledgeModes with the
 * extension's 6 explanation styles). Legacy DB rows map via [fromStored].
 */
enum class ExplainStyle(
    val label: String,
    val description: String,
    /**
     * Share of the page this style aims to produce.
     *
     * These are what make the chips mean something. Until now the style only reached the
     * structured breakdown, so choosing Concise and choosing Detailed produced identical
     * output — the chips looked like controls but changed nothing about the page you read.
     */
    val condenseRatio: Float,
    /** Appended to the condense prompt to shape the writing, not just its length. */
    val condenseInstruction: String,
) {
    Auto(
        "Auto", "Pick the single best explanation style for this text automatically.",
        condenseRatio = 0.34f,
        condenseInstruction = "Retell it as continuous prose in the voice of the original.",
    ),
    Concise(
        "Concise", "Compress aggressively and keep only essential meaning.",
        condenseRatio = 0.18f,
        condenseInstruction =
            "Be ruthless. Keep every event and fact but say each in as few words as it takes. " +
                "Short sentences. No scene-setting, no restatement, no flourish.",
    ),
    Detailed(
        "Detailed", "Explain thoroughly with depth, nuance, and hidden meaning.",
        condenseRatio = 0.55f,
        condenseInstruction =
            "Keep the nuance and the reasoning, not only what happened. Where the page implies " +
                "something without stating it, make it explicit in the retelling.",
    ),
    Bullets(
        "Bullets", "Summarize instantly as skimmable bullet points.",
        condenseRatio = 0.22f,
        condenseInstruction =
            "Write it as a list. One short line per point, in the order the page makes them, " +
                "each beginning with \"- \". Ignore the instruction above about continuous prose.",
    ),
    Analogy(
        "Analogy", "Explain through a vivid, relatable analogy.",
        condenseRatio = 0.30f,
        condenseInstruction =
            "Open with one concrete everyday comparison that carries the main idea, then retell " +
                "the page through it. Keep the real names and events.",
    ),
    Steps(
        "Steps", "Break it down step-by-step for exam-ready understanding.",
        condenseRatio = 0.30f,
        condenseInstruction =
            "Write it as numbered steps in the order the page presents them. One step per idea, " +
                "each a full sentence. Ignore the instruction above about continuous prose.",
    ),
    ;

    /** Styles whose output is meant to be a list rather than prose. */
    val isListStyle: Boolean get() = this == Bullets || this == Steps

    /**
     * Whether this style can be achieved by deleting sentences.
     *
     * Shortening a book by cutting sentences keeps the author's own prose, which is what
     * makes an abridgement still read like the book. But that only works when the target is
     * the same text, shorter. A list, a set of steps or an explanation-by-analogy is a
     * different piece of writing, and no amount of deletion produces one — those have to be
     * written, so they go to the model to be composed rather than trimmed.
     */
    val canAbridge: Boolean get() = this == Auto || this == Concise || this == Detailed

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
    /**
     * The page retold shorter, as continuous prose.
     *
     * This is the part people actually read. The structured fields below dissect the page —
     * useful, but a list of findings about a text is not the same as a shorter version of
     * it, and reading one never feels like reading the other.
     */
    val condensedProse: String = "",
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
            if (condensedProse.isNotBlank()) {
                appendLine("## Shorter version")
                appendLine(condensedProse)
                appendLine()
            }
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
    /**
     * The photographed page, when there is one.
     *
     * Gemma 4 reads images, so a snap does not need its text transcribed before it can be
     * condensed — the model can do both in a single pass. Recognised text is still kept
     * alongside for search and for comparing against the original, but it is no longer on
     * the critical path to a result.
     */
    val imagePath: String = "",
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
