package com.snapaie.android.domain.library

import com.snapaie.android.data.local.ChatSessionEntity
import com.snapaie.android.data.local.KnowledgeScan
import com.snapaie.android.data.local.NoteEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class LibraryTab(val label: String) {
    Explanations("Explanations"),
    Chats("Chats"),
    Notes("Notes"),
}

/** Time window chips, ported from the extension's history date filter. */
enum class LibraryRange(val label: String) {
    All("All time"),
    Today("Today"),
    Week("7 days"),
    ;

    companion object {
        fun fromStored(value: String): LibraryRange =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: All
    }
}

enum class LibrarySort(val label: String) {
    Newest("Newest"),
    Oldest("Oldest"),
    BestCompression("Best compression"),
    ;

    companion object {
        fun fromStored(value: String): LibrarySort =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: Newest
    }
}

/**
 * Search + filter + sort for the Library, ported from the extension's history
 * panel (subtab, search box, date filter).
 *
 * Pure functions on already-loaded lists: a local library is small enough that
 * filtering in memory beats a LIKE query per keystroke, and it keeps the
 * behaviour unit-testable without Room.
 *
 * `BestCompression` only orders scans; for chats and notes it falls back to
 * newest-first, since neither has a compression score.
 */
object LibraryFilter {

    fun scans(
        scans: List<KnowledgeScan>,
        query: String,
        range: LibraryRange,
        sort: LibrarySort,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<KnowledgeScan> {
        val terms = tokenize(query)
        val cutoff = cutoffMillis(range, nowMillis)
        val filtered = scans.filter { scan ->
            scan.createdAtMillis >= cutoff && matchesAll(terms, scanHaystack(scan))
        }
        return when (sort) {
            LibrarySort.Newest -> filtered.sortedByDescending { it.createdAtMillis }
            LibrarySort.Oldest -> filtered.sortedBy { it.createdAtMillis }
            LibrarySort.BestCompression ->
                filtered.sortedWith(
                    compareByDescending<KnowledgeScan> { it.result.compressionScore }
                        .thenByDescending { it.createdAtMillis },
                )
        }
    }

    fun chats(
        sessions: List<ChatSessionEntity>,
        query: String,
        range: LibraryRange,
        sort: LibrarySort,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<ChatSessionEntity> {
        val terms = tokenize(query)
        val cutoff = cutoffMillis(range, nowMillis)
        val filtered = sessions.filter { session ->
            session.updatedAtMillis >= cutoff &&
                matchesAll(terms, listOf(session.title, session.persona, session.appearance))
        }
        return if (sort == LibrarySort.Oldest) {
            filtered.sortedBy { it.updatedAtMillis }
        } else {
            filtered.sortedByDescending { it.updatedAtMillis }
        }
    }

    fun notes(
        notes: List<NoteEntity>,
        query: String,
        range: LibraryRange,
        sort: LibrarySort,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<NoteEntity> {
        val terms = tokenize(query)
        val cutoff = cutoffMillis(range, nowMillis)
        val filtered = notes.filter { note ->
            note.createdAtMillis >= cutoff && matchesAll(terms, listOf(note.text))
        }
        return if (sort == LibrarySort.Oldest) {
            filtered.sortedBy { it.createdAtMillis }
        } else {
            filtered.sortedByDescending { it.createdAtMillis }
        }
    }

    /** Every scan field worth searching — title, the AI output, and the source text. */
    private fun scanHaystack(scan: KnowledgeScan): List<String> = buildList {
        add(scan.bookTitle)
        add(scan.mode.label)
        add(scan.sourcePreview)
        add(scan.sourceText)
        add(scan.result.conciseMeaning)
        add(scan.result.coreIdea)
        add(scan.result.authorIntent)
        add(scan.result.simplifiedExplanation)
        add(scan.result.hiddenMeaning)
        addAll(scan.result.actionableInsights)
        addAll(scan.result.keyQuotesToKeep)
        addAll(scan.result.importantVocabulary.map { it.word })
    }

    /** All terms must appear somewhere — typing two words narrows instead of widening. */
    private fun matchesAll(terms: List<String>, haystack: List<String>): Boolean {
        if (terms.isEmpty()) return true
        return terms.all { term ->
            haystack.any { field -> field.contains(term, ignoreCase = true) }
        }
    }

    private fun tokenize(query: String): List<String> =
        query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }

    private fun cutoffMillis(range: LibraryRange, nowMillis: Long): Long = when (range) {
        LibraryRange.All -> Long.MIN_VALUE
        LibraryRange.Today ->
            Instant.ofEpochMilli(nowMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        LibraryRange.Week -> nowMillis - SEVEN_DAYS_MILLIS
    }

    private const val SEVEN_DAYS_MILLIS = 7L * 24 * 60 * 60 * 1000
}

/**
 * Groups scans into dated sections for the history view.
 *
 * A flat reverse-chronological list answers "what is newest" but not "what did I read on
 * Tuesday", which is the question someone actually returns to their history with. Days are
 * calendar days in the device's own zone, matching how [LibraryRange.Today] already works,
 * so the two never disagree about where midnight is.
 */
object LibraryHistory {

    data class Day(val label: String, val startOfDayMillis: Long, val scans: List<KnowledgeScan>)

    fun byDay(
        scans: List<KnowledgeScan>,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
    ): List<Day> = scans
        .sortedByDescending { it.createdAtMillis }
        .groupBy { Instant.ofEpochMilli(it.createdAtMillis).atZone(zone).toLocalDate() }
        .map { (date, group) ->
            Day(
                label = labelFor(date, today),
                startOfDayMillis = date.atStartOfDay(zone).toInstant().toEpochMilli(),
                scans = group,
            )
        }
        .sortedByDescending { it.startOfDayMillis }

    private fun labelFor(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> {
            val pattern = if (date.year == today.year) "d MMMM" else "d MMMM yyyy"
            date.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
        }
    }
}
