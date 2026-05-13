package com.snapaie.android.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object ReadingStreak {
    /** Consecutive calendar days ending today or yesterday, based on distinct scan dates. */
    fun fromScanTimestamps(millis: Iterable<Long>, zone: ZoneId = ZoneId.systemDefault()): Int {
        val stamps = millis.toList()
        if (stamps.isEmpty()) return 0
        val dates = stamps
            .map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            .distinct()
            .sortedDescending()
        val today = LocalDate.now(zone)
        val mostRecent = dates.first()
        if (mostRecent != today && mostRecent != today.minusDays(1)) return 0
        var streak = 1
        var expected = mostRecent.minusDays(1)
        for (i in 1 until dates.size) {
            val day = dates[i]
            when {
                day == expected -> {
                    streak++
                    expected = expected.minusDays(1)
                }
                day.isBefore(expected) -> break
            }
        }
        return streak
    }
}
