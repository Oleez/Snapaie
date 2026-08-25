package com.snapaie.android.domain.condense

/**
 * Shortening without a model.
 *
 * The floor under every other path. Keeps the opening of each paragraph in order and drops
 * the tail, which preserves sequence, names and events at the cost of the prose. It reads
 * worse than a real retelling — but a rough paragraph in the right place is recoverable,
 * and an empty screen is what makes people think the feature is broken.
 *
 * Pure, so it works with no model installed, no network, and can be tested directly.
 */
object ExtractiveCondenser {

    fun shorten(sourceText: String, budgetWords: Int): String {
        val paragraphs = sourceText.split(Regex("""\n\s*\n""")).map { it.trim() }.filter { it.isNotBlank() }
        if (paragraphs.isEmpty()) return sourceText.trim()

        // The floor exists so a beat is never cut to a stub, but it must never exceed the
        // thing being shortened. Raising a 45-word page's budget to 60 meant every sentence
        // fitted, so the "shortened" version was the page itself, returned unchanged — the
        // local path looked like it was working while doing nothing at all.
        val sourceWords = countWords(sourceText)
        val floor = minOf(BudgetGovernor.MIN_BEAT_WORDS, sourceWords / 2)
        val budget = budgetWords.coerceAtLeast(floor).coerceAtMost(sourceWords)
        val perParagraph = (budget / paragraphs.size)
            .coerceAtLeast(minOf(MIN_FALLBACK_WORDS_PER_PARAGRAPH, budget))

        return paragraphs.joinToString("\n\n") { paragraph ->
            val sentences = splitSentences(paragraph)
            val kept = StringBuilder()
            var words = 0
            for (sentence in sentences) {
                val cost = countWords(sentence)
                // Stop *before* going over rather than after. Breaking afterwards meant
                // every paragraph overshot by a whole sentence, and at small budgets that
                // added up to output barely shorter than the page it came from. The first
                // sentence is always kept, so a paragraph is never reduced to nothing.
                if (kept.isNotEmpty() && words + cost > perParagraph) break
                if (kept.isNotEmpty()) kept.append(' ')
                kept.append(sentence.trim())
                words += cost
            }
            kept.toString()
        }.trim()
    }

    private fun splitSentences(text: String): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        var index = 0
        while (index < text.length) {
            if (text[index] in ".!?") {
                var end = index + 1
                while (end < text.length && text[end] in "\"')]\u2019\u201D") end++
                if (end >= text.length || text[end].isWhitespace()) {
                    result += text.substring(start, end)
                    start = end
                    index = end
                    continue
                }
            }
            index++
        }
        if (start < text.length) result += text.substring(start)
        return result.map { it.trim() }.filter { it.isNotBlank() }
    }

    private const val MIN_FALLBACK_WORDS_PER_PARAGRAPH = 12

    private fun countWords(text: String): Int = text.split(Regex("""\s+""")).count { it.isNotBlank() }
}
