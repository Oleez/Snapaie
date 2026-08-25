package com.snapaie.android.domain.condense

/**
 * Abridging a passage that is larger than the model's context window.
 *
 * The window used to decide how much of a page could be condensed at all: text beyond it
 * was truncated, or sent anyway and silently lost. Neither is acceptable when the promise
 * is that nothing gets skipped.
 *
 * Deletion makes the alternative easy. Choosing which sentences to keep is a decision each
 * sentence can take almost independently, so the passage can be walked a window at a time —
 * first run, second run, third — and the kept indices concatenated at the end. Order is
 * preserved because the runs are consecutive and the indices are global, and the result is
 * identical in kind to a single pass: the author's own sentences, in the author's order.
 *
 * This is what a retelling could not do. Retelling each run separately would restart the
 * voice, re-introduce characters and repeat what the previous run had established, which is
 * the whole reason the book pipeline carries a ledger between passages. Nothing needs to
 * travel between runs here, because nothing is being written.
 */
object ChunkedAbridgement {

    /**
     * What a walk produced: the indices to keep, and how honest we can be about them.
     *
     * [runsFallenBack] is not bookkeeping. A run the model could not answer is chosen by
     * the local heuristic instead, and the reader is told which passages that happened to.
     * Without this count the beat would claim the model made a choice it never made.
     */
    data class Walk(
        val keep: List<Int>,
        val runs: Int,
        val runsFallenBack: Int,
    ) {
        val usedFallback: Boolean get() = runsFallenBack > 0
    }

    /** "12. " plus a line break, the cost of numbering one sentence for the model. */
    const val NUMBER_PREFIX_CHARS = 6

    /**
     * Splits [sentences] into consecutive runs whose numbered form fits [maxChars].
     *
     * A sentence longer than a whole window gets a run to itself rather than being dropped:
     * the model may refuse it, but the local fallback can still keep it, and keeping an
     * over-long sentence is better than losing it.
     */
    fun chunk(sentences: List<Abridger.Sentence>, maxChars: Int): List<List<Abridger.Sentence>> {
        if (sentences.isEmpty() || maxChars <= 0) return emptyList()
        val runs = mutableListOf<List<Abridger.Sentence>>()
        var current = mutableListOf<Abridger.Sentence>()
        var used = 0

        sentences.forEach { sentence ->
            val cost = sentence.text.length + NUMBER_PREFIX_CHARS
            if (current.isNotEmpty() && used + cost > maxChars) {
                runs += current
                current = mutableListOf()
                used = 0
            }
            current += sentence
            used += cost
        }
        if (current.isNotEmpty()) runs += current
        return runs
    }

    /**
     * Walks the passage a run at a time and returns every index worth keeping.
     *
     * [askModel] is handed the run renumbered from zero — small numbers are easier for a
     * model to emit accurately than an offset that climbs into the hundreds — and returns
     * its raw reply, or null when there is no usable answer. A run the model cannot handle
     * falls back on its own; one bad run costs that run, not the passage.
     */
    suspend fun keepIndices(
        sentences: List<Abridger.Sentence>,
        targetWords: Int,
        maxChunkChars: Int,
        askModel: suspend (numbered: String, count: Int, targetWords: Int) -> String?,
    ): Walk {
        val runs = chunk(sentences, maxChunkChars)
        if (runs.isEmpty()) return Walk(emptyList(), runs = 0, runsFallenBack = 0)

        val totalWords = sentences.sumOf { it.words }.coerceAtLeast(1)
        val keep = mutableListOf<Int>()
        var spent = 0
        var fellBack = 0

        runs.forEachIndexed { position, run ->
            // Each run gets the share of the budget its share of the source deserves, with
            // the last run taking whatever is left so rounding cannot lose or invent words.
            val runWords = run.sumOf { it.words }
            val share = if (position == runs.lastIndex) {
                (targetWords - spent).coerceAtLeast(0)
            } else {
                (targetWords.toLong() * runWords / totalWords).toInt()
            }
            spent += share

            val chosen = chooseRun(run, share, askModel)
            if (chosen.fromModel) keep += chosen.keep else { keep += chosen.keep; fellBack++ }
        }
        return Walk(keep.distinct().sorted(), runs = runs.size, runsFallenBack = fellBack)
    }

    /** One run's outcome, and whether the model or the local heuristic decided it. */
    private data class RunChoice(val keep: List<Int>, val fromModel: Boolean)

    private suspend fun chooseRun(
        run: List<Abridger.Sentence>,
        targetWords: Int,
        askModel: suspend (numbered: String, count: Int, targetWords: Int) -> String?,
    ): RunChoice {
        // Already inside its share: keep the run whole rather than cutting for no reason.
        // Nothing was cut, so nothing was guessed at — this is not a fallback.
        if (run.sumOf { it.words } <= targetWords) return RunChoice(run.map { it.index }, fromModel = true)

        val numbered = run
            .mapIndexed { local, sentence -> "$local. ${sentence.text}" }
            .joinToString(System.lineSeparator())

        val reply = askModel(numbered, run.size, targetWords)
        if (reply != null) {
            val local = Abridger.parseKeepList(reply, run.size)
            val kept = local.mapNotNull { run.getOrNull(it)?.index }
            // A run answered with one sentence out of forty has been deleted, not abridged.
            if (kept.isNotEmpty() && keptWords(run, local) >= targetWords / 3) {
                return RunChoice(kept, fromModel = true)
            }
        }
        return RunChoice(Abridger.chooseLocally(run, targetWords), fromModel = false)
    }

    private fun keptWords(run: List<Abridger.Sentence>, local: List<Int>): Int =
        local.sumOf { run.getOrNull(it)?.words ?: 0 }
}
