package com.snapaie.android.domain.library

import com.snapaie.android.data.local.KnowledgeScan
import com.snapaie.android.data.model.ExplainStyle
import com.snapaie.android.data.model.KnowledgeResult
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryHistoryTest {

    private val zone = ZoneId.of("Europe/London")
    private val today = LocalDate.of(2026, 3, 14)

    private fun scanAt(date: LocalDate, hour: Int, id: Long) = KnowledgeScan(
        id = id,
        createdAtMillis = date.atStartOfDay(zone).plusHours(hour.toLong()).toInstant().toEpochMilli(),
        mode = ExplainStyle.Auto,
        bookTitle = "Book",
        sourcePreview = "",
        sourceText = "",
        result = KnowledgeResult(),
        wordsIn = 0,
        wordsOut = 0,
    )

    @Test
    fun `scans group into days, newest day first`() {
        val scans = listOf(
            scanAt(today, 9, 1),
            scanAt(today, 20, 2),
            scanAt(today.minusDays(1), 12, 3),
            scanAt(today.minusDays(40), 12, 4),
        )
        val days = LibraryHistory.byDay(scans, zone, today)
        assertEquals(listOf("Today", "Yesterday", "2 February"), days.map { it.label })
        assertEquals(2, days.first().scans.size)
    }

    @Test
    fun `within a day the newest scan is first`() {
        val days = LibraryHistory.byDay(
            listOf(scanAt(today, 9, 1), scanAt(today, 20, 2)),
            zone,
            today,
        )
        assertEquals(listOf(2L, 1L), days.single().scans.map { it.id })
    }

    @Test
    fun `a day just before midnight is not folded into the next day`() {
        // The boundary that matters: 23:59 belongs to its own calendar day, the same way
        // LibraryRange.Today already decides where midnight is.
        val days = LibraryHistory.byDay(
            listOf(scanAt(today.minusDays(1), 23, 1), scanAt(today, 0, 2)),
            zone,
            today,
        )
        assertEquals(listOf("Today", "Yesterday"), days.map { it.label })
    }

    @Test
    fun `a scan from a previous year carries its year`() {
        val days = LibraryHistory.byDay(listOf(scanAt(today.minusYears(1), 10, 1)), zone, today)
        assertTrue(days.single().label.endsWith("2025"))
    }

    @Test
    fun `no scans means no days`() {
        assertTrue(LibraryHistory.byDay(emptyList(), zone, today).isEmpty())
    }
}
