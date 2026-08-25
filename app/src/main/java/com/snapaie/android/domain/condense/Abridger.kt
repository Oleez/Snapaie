package com.snapaie.android.domain.condense

/**
 * Shortening by deletion rather than rewriting.
 *
 * This is the difference between an abridged edition and a summary, and it is why a good
 * abridgement still reads like the book: the sentences that survive are the author's own,
 * untouched. Nothing is paraphrased, so nothing can be blunted, mis-attributed or
 * invented — the model's only job is to choose what goes.
 *
 * It is also far cheaper on a phone. Asking a model to retell nine hundred words means
 * generating several hundred, token by token; asking which sentences to keep means
 * generating a list of numbers. That is the difference between minutes and seconds, and it
 * is why this can run over a whole book at all.
 */
object Abridger {

    /** A sentence of the source, with its position. */
    data class Sentence(val index: Int, val text: String, val words: Int)

    private val WORD = Regex("""\S+""")

    /** Closing punctuation that can follow a full stop before the sentence really ends. */
    private const val TRAILING = "\"')]}\u2019\u201D"

    /**
     * Splits prose into sentences.
     *
     * Deliberately conservative about abbreviations — "Mr. Samsa" must not become two
     * sentences, or the abridger is offered fragments it cannot sensibly keep or cut.
     */
    fun split(text: String): List<Sentence> {
        val out = mutableListOf<Sentence>()
        val trimmed = text.trim()
        var start = 0
        var i = 0

        while (i < trimmed.length) {
            val ch = trimmed[i]
            if (ch == '.' || ch == '!' || ch == '?') {
                var end = i + 1
                while (end < trimmed.length && trimmed[end] in TRAILING) end++

                val atEnd = end >= trimmed.length
                val followedBySpace = !atEnd && trimmed[end].isWhitespace()
                if ((atEnd || followedBySpace) && !endsWithAbbreviation(trimmed, i)) {
                    val piece = trimmed.substring(start, end).trim()
                    if (piece.isNotEmpty()) {
                        out += Sentence(out.size, piece, WORD.findAll(piece).count())
                    }
                    start = end
                    i = end
                    continue
                }
            }
            i++
        }

        val tail = trimmed.substring(start).trim()
        if (tail.isNotEmpty()) out += Sentence(out.size, tail, WORD.findAll(tail).count())
        return out
    }

    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "st", "jr", "sr", "vs", "etc", "no", "vol", "ch",
    )

    private fun endsWithAbbreviation(text: String, dotIndex: Int): Boolean {
        if (text[dotIndex] != '.') return false
        var start = dotIndex
        while (start > 0 && text[start - 1].isLetter()) start--
        val word = text.substring(start, dotIndex).lowercase()
        if (word in ABBREVIATIONS) return true
        // A single capital letter is an initial: "F. Kafka".
        return word.length == 1 && text[start].isUpperCase()
    }

    /**
     * Reads the indices the model chose to keep.
     *
     * Tolerant on purpose: replies arrive as "1, 3, 4", as "[1,3,4]", with prose around
     * them, or with the numbers on separate lines. Anything outside the valid range is
     * dropped rather than failing the beat.
     */
    fun parseKeepList(raw: String, sentenceCount: Int): List<Int> {
        if (sentenceCount <= 0) return emptyList()
        return Regex("""\d+""").findAll(raw)
            .mapNotNull { it.value.toIntOrNull() }
            .filter { it in 0 until sentenceCount }
            .distinct()
            .sorted()
            .toList()
    }

    /**
     * Rebuilds the passage from the chosen sentences.
     *
     * Sorted, not taken in the order the model listed them. A model that answers "3, 0, 2"
     * means those three sentences, not that order — honouring its ordering would scramble
     * the prose while every individual sentence still looked untouched.
     */
    fun assemble(sentences: List<Sentence>, keep: List<Int>): String =
        keep.distinct().sorted()
            .mapNotNull { index -> sentences.getOrNull(index)?.text }
            .joinToString(" ")
            .trim()

    /**
     * Chooses sentences without a model.
     *
     * The floor under the model path, and what runs when there is no model at all. It keeps
     * the opening of each paragraph and drops from the middle outwards, which preserves the
     * shape of a scene better than trimming the tail: the first sentence usually carries
     * who and where, and the last usually carries what changed.
     */
    fun chooseLocally(sentences: List<Sentence>, targetWords: Int): List<Int> {
        if (sentences.isEmpty()) return emptyList()
        val total = sentences.sumOf { it.words }
        if (total <= targetWords) return sentences.map { it.index }

        val keep = linkedSetOf(sentences.first().index, sentences.last().index)
        var words = sentences.first().words + if (sentences.size > 1) sentences.last().words else 0

        // Then take sentences from the front, which is where a scene establishes itself.
        for (sentence in sentences.drop(1).dropLast(1)) {
            if (words + sentence.words > targetWords) continue
            keep += sentence.index
            words += sentence.words
        }
        return keep.sorted()
    }

    /** Words in [text], counted the same way everywhere else in the pipeline does. */
    fun countWords(text: String): Int = WORD.findAll(text).count()
}
