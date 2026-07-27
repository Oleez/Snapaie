package com.snapaie.android.domain.recall

import java.util.concurrent.TimeUnit

/**
 * Spaced-repetition schedule ported exactly from the extension's
 * getPracticeNextDueAt (popup.js:13833): days [1, 3, 7, 14, 30] indexed by
 * reviewCount, with a floor raised by topic strength.
 */
object SpacedRepetitionScheduler {

    private val scheduleDays = intArrayOf(1, 3, 7, 14, 30)

    fun intervalDays(reviewCount: Int, strengthLevel: Int): Int {
        var index = reviewCount.coerceIn(0, scheduleDays.lastIndex)
        val strengthFloor = when {
            strengthLevel >= 92 -> 4
            strengthLevel >= 78 -> 3
            strengthLevel >= 58 -> 2
            strengthLevel >= 35 -> 1
            else -> 0
        }
        if (strengthFloor > index) index = strengthFloor
        return scheduleDays[index]
    }

    fun nextDueAt(reviewCount: Int, strengthLevel: Int, nowMillis: Long = System.currentTimeMillis()): Long =
        nowMillis + TimeUnit.DAYS.toMillis(intervalDays(reviewCount, strengthLevel).toLong())

    /** Strength delta per review session: perfect +12, mostly right +8, poor -5. */
    fun strengthDelta(correct: Int, total: Int): Int = when {
        total <= 0 -> 0
        correct == total -> 12
        correct * 2 >= total -> 8
        else -> -5
    }

    fun isDueNow(nextDueAtMillis: Long, nowMillis: Long = System.currentTimeMillis()): Boolean =
        nextDueAtMillis <= nowMillis

    fun isDueSoon(nextDueAtMillis: Long, nowMillis: Long = System.currentTimeMillis()): Boolean =
        !isDueNow(nextDueAtMillis, nowMillis) &&
            nextDueAtMillis - nowMillis <= TimeUnit.HOURS.toMillis(36)
}
