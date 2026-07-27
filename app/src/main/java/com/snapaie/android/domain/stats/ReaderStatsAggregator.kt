package com.snapaie.android.domain.stats

import com.snapaie.android.data.local.KnowledgeScan
import java.util.concurrent.TimeUnit

data class WeeklyStats(
    val pages: Int = 0,
    val minutesSaved: Int = 0,
    val avgCompression: Int = 0,
    val wordsIn: Int = 0,
    val wordsOut: Int = 0,
)

/** Rolling 7-day aggregation for the weekly Reader Report. */
object ReaderStatsAggregator {

    fun weekly(scans: List<KnowledgeScan>, nowMillis: Long = System.currentTimeMillis()): WeeklyStats {
        val cutoff = nowMillis - TimeUnit.DAYS.toMillis(7)
        val recent = scans.filter { it.createdAtMillis >= cutoff }
        if (recent.isEmpty()) return WeeklyStats()
        return WeeklyStats(
            pages = recent.size,
            minutesSaved = recent.sumOf { it.result.estimatedTimeSavedMinutes },
            avgCompression = recent.map { it.result.compressionScore }
                .filter { it > 0 }
                .average()
                .takeIf { !it.isNaN() }
                ?.toInt() ?: 0,
            wordsIn = recent.sumOf { it.wordsIn },
            wordsOut = recent.sumOf { it.wordsOut },
        )
    }
}
