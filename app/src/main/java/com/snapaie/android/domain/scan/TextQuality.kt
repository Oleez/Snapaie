package com.snapaie.android.domain.scan

/**
 * Judges whether recognised page text is good enough to work from.
 *
 * A text recogniser fails loudly on a blank page and quietly on a bad one: it returns
 * something, so nothing downstream notices, and the user gets a confident condensation of
 * garbage. These are the shapes that failure actually takes on a photographed book — a
 * handful of characters from a curved page, words shattered into fragments by a bad
 * threshold, or a wall of symbols where the recogniser gave up on the script.
 *
 * Pure, so the thresholds can be pinned by tests rather than guessed at.
 */
object TextQuality {

    /** How badly recognised text reads. */
    enum class Verdict {
        /** Usable as-is. */
        GOOD,

        /** Readable but suspect — worth a second opinion if one is cheap. */
        WEAK,

        /** Not worth condensing. */
        UNUSABLE,
    }

    private val WORD = Regex("""[\p{L}\p{N}][\p{L}\p{N}'’-]*""")

    fun assess(text: String): Verdict {
        val trimmed = text.trim()
        if (trimmed.length < MIN_CHARS) return Verdict.UNUSABLE

        val words = WORD.findAll(trimmed).map { it.value }.toList()
        if (words.size < MIN_WORDS) return Verdict.UNUSABLE

        // Letters and digits as a share of everything that is not whitespace. Real prose
        // sits well above this even with heavy punctuation; recogniser noise does not.
        val dense = trimmed.count { it.isLetterOrDigit() }
        val solid = trimmed.count { !it.isWhitespace() }
        val alphaRatio = if (solid == 0) 0f else dense.toFloat() / solid
        if (alphaRatio < MIN_ALPHA_RATIO) return Verdict.UNUSABLE

        // A page shattered into one- and two-character fragments is the classic symptom of
        // a threshold that ate the thin strokes.
        val shortWords = words.count { it.length <= 2 }.toFloat() / words.size
        val averageWordLength = words.sumOf { it.length }.toFloat() / words.size

        return when {
            shortWords > MAX_SHORT_WORD_RATIO -> Verdict.UNUSABLE
            averageWordLength < MIN_AVERAGE_WORD_LENGTH -> Verdict.WEAK
            alphaRatio < WEAK_ALPHA_RATIO -> Verdict.WEAK
            words.size < WEAK_WORD_COUNT -> Verdict.WEAK
            else -> Verdict.GOOD
        }
    }

    private const val MIN_CHARS = 24
    private const val MIN_WORDS = 6
    private const val MIN_ALPHA_RATIO = 0.55f
    private const val WEAK_ALPHA_RATIO = 0.72f
    private const val MAX_SHORT_WORD_RATIO = 0.62f
    private const val MIN_AVERAGE_WORD_LENGTH = 2.9f
    private const val WEAK_WORD_COUNT = 25
}
