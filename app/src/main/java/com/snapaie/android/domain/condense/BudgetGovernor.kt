package com.snapaie.android.domain.condense

/**
 * Decides how many words each beat is allowed to produce so the finished book lands on
 * the length the user asked for.
 *
 * A fixed ratio does not work. Beats vary in density, and the model overshoots or
 * undershoots any given target by a wide margin, so a 500-page book condensed at a flat
 * 30% can finish 40% long. The governor closes the loop: after every beat it recomputes
 * the ratio the *remaining* source needs in order to hit the *remaining* budget, and
 * nudges the next beat's allowance toward it.
 *
 * The nudge is clamped, which is the important part. Left unbounded, an early overshoot
 * would starve later beats down to nothing and the story would visibly collapse in its
 * final chapters — precisely the "skipping" this whole pipeline exists to avoid. Better to
 * land a few percent long than to gut the ending.
 *
 * Pure and resumable: state is two running totals, so a job restarted after process death
 * reconstructs the governor exactly from what the database already holds.
 */
class BudgetGovernor(
    private val totalTargetWords: Int,
    private val totalSourceWords: Int,
    producedWords: Int = 0,
    consumedSourceWords: Int = 0,
) {

    var producedWords: Int = producedWords
        private set

    var consumedSourceWords: Int = consumedSourceWords
        private set

    /** The ratio the book as a whole is aiming for. */
    val globalRatio: Float
        get() = if (totalSourceWords <= 0) DEFAULT_RATIO else totalTargetWords.toFloat() / totalSourceWords

    /** Words of output still owed, never negative. */
    val remainingBudget: Int get() = (totalTargetWords - producedWords).coerceAtLeast(0)

    val remainingSourceWords: Int get() = (totalSourceWords - consumedSourceWords).coerceAtLeast(0)

    /**
     * Words this beat should aim for. [srcWords] is the beat's own source length, so a
     * dense 1,200-word beat is allowed more output than a 400-word one.
     */
    fun budgetFor(srcWords: Int): Int {
        if (srcWords <= 0) return 0
        val nominal = srcWords * globalRatio
        if (nominal <= 0f) return MIN_BEAT_WORDS

        val expectedRemaining = remainingSourceWords * globalRatio
        val correction = if (expectedRemaining > 0f) {
            (remainingBudget / expectedRemaining).coerceIn(MIN_CORRECTION, MAX_CORRECTION)
        } else {
            1f
        }

        val governed = nominal * correction
        return governed.toInt()
            .coerceAtLeast(MIN_BEAT_WORDS)
            // Never ask for more words out than went in; that is expansion, not condensing.
            .coerceAtMost(srcWords)
    }

    /** Folds a finished beat into the running totals. */
    fun record(producedWords: Int, srcWords: Int) {
        this.producedWords += producedWords.coerceAtLeast(0)
        this.consumedSourceWords += srcWords.coerceAtLeast(0)
    }

    companion object {
        /** Floor for any beat: below this there is not enough room to retell a scene. */
        const val MIN_BEAT_WORDS = 60

        private const val DEFAULT_RATIO = 0.3f

        /**
         * How far a single beat may be pushed from its nominal share. Correcting harder
         * than this makes the drift visible as a change of pace in the prose.
         */
        private const val MIN_CORRECTION = 0.65f
        private const val MAX_CORRECTION = 1.35f
    }
}

/** Converts what the user asked for into a word budget for the whole book. */
object CondenseTarget {

    /**
     * Words per output page at the default 6x9 page, 11pt, with normal leading. Measured
     * from the layout engine rather than guessed, and recalculated there if the page size
     * changes; a wrong constant here shows up as a book that misses its page count.
     */
    const val WORDS_PER_PAGE = 340

    fun wordsForPages(pages: Int): Int = (pages.coerceAtLeast(1) * WORDS_PER_PAGE)

    fun wordsForPercent(sourceWords: Int, percent: Int): Int =
        (sourceWords.toLong() * percent.coerceIn(1, 100) / 100).toInt().coerceAtLeast(WORDS_PER_PAGE)

    /**
     * Whether a target is aggressive enough to need the two-pass ladder.
     *
     * Asking a 2B model for a 10:1 condensation in one step is where skipping comes from:
     * with that little room it starts summarising chapters instead of retelling them.
     * Going via an intermediate 30% keeps every step a retelling.
     */
    fun needsLadder(targetWords: Int, sourceWords: Int): Boolean =
        sourceWords > 0 && targetWords.toFloat() / sourceWords < LADDER_THRESHOLD

    /** Ratio of the first ladder pass; its output is a usable book in its own right. */
    const val FIRST_PASS_RATIO = 0.3f

    private const val LADDER_THRESHOLD = 0.2f
}
