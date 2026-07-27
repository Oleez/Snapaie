package com.snapaie.android.domain.chat

data class LengthOverride(val target: String, val triggerLabel: String)

/** Ported from the extension's detectUserLengthOverride (chat-prompt-utils.js:88-116). */
object LengthOverrideDetector {

    private val wordCapA = Regex("\\b(no\\s+more\\s+than|under|at\\s+most|max(imum)?|within|less\\s+than)\\s+(\\d{1,3})\\s+words?\\b", RegexOption.IGNORE_CASE)
    private val wordCapB = Regex("\\b(?:in\\s+|just\\s+)?(\\d{1,3})\\s+words?\\b", RegexOption.IGNORE_CASE)
    private val oneSentence = Regex("\\b(one|1)\\s+sentence\\b|\\bin\\s+a\\s+sentence\\b", RegexOption.IGNORE_CASE)
    private val twoSentences = Regex("\\b(two|2)\\s+sentences\\b", RegexOption.IGNORE_CASE)
    private val threeSentences = Regex("\\b(three|3)\\s+sentences\\b", RegexOption.IGNORE_CASE)
    private val oneLine = Regex("\\b(one|1)\\s+line\\b|\\bin\\s+one\\s+line\\b", RegexOption.IGNORE_CASE)
    private val tldr = Regex("\\btl;?dr\\b|\\b(summary|summari[sz]e|sum\\s*it\\s*up)\\b", RegexOption.IGNORE_CASE)
    private val brief = Regex("\\b(very\\s+)?(brief|briefly|concise|shorter|shorten|condense|trim|short(er)?\\s+answer|keep\\s+it\\s+short|make\\s+it\\s+short(er)?|less\\s+words?|fewer\\s+words?|minimal|quick(ly)?)\\b", RegexOption.IGNORE_CASE)

    fun detect(message: String): LengthOverride? {
        wordCapA.find(message)?.let { m ->
            val words = m.groupValues[3]
            return LengthOverride("<=$words words", "$words words")
        }
        wordCapB.find(message)?.let { m ->
            val words = m.groupValues[1]
            return LengthOverride("<=$words words", "$words words")
        }
        if (oneSentence.containsMatchIn(message)) return LengthOverride("1 sentence (max 25 words)", "one sentence")
        if (twoSentences.containsMatchIn(message)) return LengthOverride("2 sentences (max 45 words)", "two sentences")
        if (threeSentences.containsMatchIn(message)) return LengthOverride("3 sentences (max 70 words)", "three sentences")
        if (oneLine.containsMatchIn(message)) return LengthOverride("1 line (max 20 words)", "one line")
        if (tldr.containsMatchIn(message)) return LengthOverride("2-3 sentence summary (max 60 words)", "summary / TL;DR")
        if (brief.containsMatchIn(message)) {
            return LengthOverride("concise: 1-3 short sentences (max 60 words), no bullets, no headings", "concise / short")
        }
        return null
    }
}
