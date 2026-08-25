package com.snapaie.android.domain.condense

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Choosing which sentences to keep, without a model.
 *
 * Worth being clear about what this is. Deciding which sentences carry a passage is
 * *extractive summarisation*, and it was a solved problem long before language models —
 * TextRank and its relatives have done it well since 2004. It is not the hard part of
 * writing; it is a ranking problem over a graph of sentence similarity, and a phone does it
 * in milliseconds.
 *
 * That matters here because the app already reduced the model's job to exactly this. Since
 * shortening became deletion rather than rewriting, the model's only output is a list of
 * indices — the same list this produces. So the model is being asked for something a
 * couple of hundred lines of arithmetic answers, for two gigabytes and a wait measured in
 * minutes.
 *
 * How it works: sentences vote for each other in proportion to how much vocabulary they
 * share, the votes are settled by power iteration, and the winners are taken in the book's
 * order until the budget is spent. Position and redundancy adjust the raw score — an
 * opening sentence establishes who and where, and a sentence that repeats one already
 * chosen adds nothing however central it looks.
 */
object SentenceRanker {

    /**
     * Words carrying no topical signal. Kept short on purpose: an aggressive list starts
     * removing the words that distinguish one passage from another.
     */
    private val STOP_WORDS = setOf(
        "a", "an", "and", "are", "as", "at", "be", "been", "but", "by", "for", "from",
        "had", "has", "have", "he", "her", "him", "his", "i", "if", "in", "into", "is",
        "it", "its", "of", "on", "or", "she", "so", "than", "that", "the", "their",
        "them", "then", "there", "they", "this", "to", "was", "were", "what", "when",
        "which", "who", "will", "with", "would", "you", "your",
    )

    private val WORD = Regex("""[\p{L}\p{N}']+""")

    /**
     * The indices worth keeping, in the order they appear in the source.
     *
     * Never returns nothing for a non-empty passage: the opening sentence is always kept,
     * because a passage that starts mid-scene reads as damage rather than as an abridgement.
     */
    fun choose(sentences: List<Abridger.Sentence>, targetWords: Int): List<Int> {
        if (sentences.isEmpty()) return emptyList()
        if (sentences.sumOf { it.words } <= targetWords) return sentences.map { it.index }

        val tokens = sentences.map { tokenise(it.text) }
        val scores = rank(tokens).toMutableList()

        // Position matters independently of vocabulary. The opening carries who and where,
        // and the close usually carries what changed; neither needs to share words with the
        // rest of the passage to be the sentence you cannot drop.
        scores[0] *= OPENING_BOOST

        val chosen = mutableListOf<Int>()
        val chosenTokens = mutableListOf<Set<String>>()
        var words = 0

        // The opening and the close are not negotiable. The first sentence carries who and
        // where, the last carries what changed, and a passage missing either reads as
        // damage rather than as an abridgement — however unremarkable their vocabulary is.
        chosen += 0
        chosenTokens += tokens[0]
        words += sentences[0].words

        // Taken even when it overshoots. A tight budget that cannot fit both bookends
        // should spend it on them rather than end the passage on whichever middle sentence
        // happened to fit — going a little long reads as an abridgement, stopping in the
        // middle of a scene reads as a bug.
        val last = sentences.lastIndex
        if (last > 0) {
            chosen += last
            chosenTokens += tokens[last]
            words += sentences[last].words
        }

        val remaining = (1 until sentences.size)
            .filterNot { it in chosen }
            .sortedByDescending { scores[it] }
        for (candidate in remaining) {
            val cost = sentences[candidate].words
            if (words + cost > targetWords) continue
            // A sentence that restates one already kept spends budget and adds nothing.
            // overlapRatio, not the ranking score: the latter is log-damped and unbounded,
            // so comparing it against a 0-to-1 threshold would mean nothing.
            if (chosenTokens.any { overlapRatio(tokens[candidate], it) > REDUNDANCY_LIMIT }) continue
            chosen += candidate
            chosenTokens += tokens[candidate]
            words += cost
        }

        return chosen.sorted().map { sentences[it].index }
    }

    /**
     * TextRank: sentences vote for each other by shared vocabulary, settled by power
     * iteration. Damping keeps a tight cluster of similar sentences from taking the whole
     * score, exactly as it does for the page-ranking algorithm this borrows from.
     */
    private fun rank(tokens: List<Set<String>>): List<Double> {
        val n = tokens.size
        if (n == 1) return listOf(1.0)

        val weights = Array(n) { DoubleArray(n) }
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val w = similarity(tokens[i], tokens[j])
                weights[i][j] = w
                weights[j][i] = w
            }
        }

        var scores = DoubleArray(n) { 1.0 / n }
        repeat(ITERATIONS) {
            val next = DoubleArray(n) { (1 - DAMPING) / n }
            for (i in 0 until n) {
                val outgoing = weights[i].sum()
                if (outgoing <= 0.0) continue
                for (j in 0 until n) {
                    if (i == j || weights[i][j] <= 0.0) continue
                    next[j] += DAMPING * scores[i] * weights[i][j] / outgoing
                }
            }
            scores = next
        }
        return scores.toList()
    }

    /**
     * Shared vocabulary, damped by length.
     *
     * The raw overlap count favours long sentences for no better reason than that they
     * contain more words, so it is divided by the logarithm of the lengths — the standard
     * correction, and the difference between ranking what a passage is about and ranking
     * which of its sentences ran longest.
     */
    private fun similarity(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val shared = a.count { it in b }
        if (shared == 0) return 0.0
        val denominator = ln(a.size + 1.0) + ln(b.size + 1.0)
        if (denominator <= 0.0) return 0.0
        return shared / denominator
    }

    private fun tokenise(text: String): Set<String> =
        WORD.findAll(text.lowercase())
            .map { it.value }
            .filter { it.length > 1 && it !in STOP_WORDS }
            .toSet()

    /** Cosine-style closeness, used only to reject near-duplicates. */
    private fun overlapRatio(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return a.count { it in b } / sqrt(a.size.toDouble() * b.size.toDouble())
    }

    private const val DAMPING = 0.85
    private const val ITERATIONS = 30
    private const val OPENING_BOOST = 1.6

    /** Above this, two sentences are saying the same thing and only one is worth keeping. */
    private const val REDUNDANCY_LIMIT = 0.65
}
