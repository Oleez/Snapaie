package com.snapaie.android.domain.condense

/**
 * Splits a passage into consecutive slices that each fit one model call.
 *
 * Joining the slices reconstructs the original text. Cuts prefer a paragraph break,
 * then a sentence boundary, and only then a space, so a slice does not start or end
 * mid-word when the passage gives us a choice.
 */
object PassageSlicer {

    fun slices(text: String, maxChars: Int): List<String> {
        if (text.isEmpty()) return emptyList()
        if (maxChars <= 0 || text.length <= maxChars) return listOf(text)
        val out = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            if (text.length - start <= maxChars) {
                out += text.substring(start)
                break
            }
            val end = cut(text, start, maxChars)
            val safe = if (end > start) end else (start + maxChars).coerceAtMost(text.length)
            out += text.substring(start, safe)
            start = safe
        }
        return out
    }

    private fun cut(text: String, start: Int, maxChars: Int): Int {
        val window = text.substring(start, (start + maxChars).coerceAtMost(text.length))
        val earliest = maxChars / 4
        val paragraph = window.lastIndexOf("\n\n")
        if (paragraph >= earliest) return start + paragraph + 2
        val sentence = lastSentenceEnd(window)
        if (sentence >= earliest) return start + sentence
        val space = window.lastIndexOf(' ')
        if (space >= earliest) return start + space + 1
        return start + window.length
    }

    /** Index just after the last sentence-ending mark, or -1 when the window has none. */
    private fun lastSentenceEnd(window: String): Int {
        var best = -1
        for (mark in SENTENCE_MARKS) {
            val at = window.lastIndexOf(mark)
            if (at >= 0 && at + mark.length > best) best = at + mark.length
        }
        return best
    }

    private val SENTENCE_MARKS = arrayOf(". ", "! ", "? ", ".\n", "!\n", "?\n")
}
