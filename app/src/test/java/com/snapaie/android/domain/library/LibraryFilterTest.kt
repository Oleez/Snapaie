package com.snapaie.android.domain.library

import com.snapaie.android.data.local.KnowledgeScan
import com.snapaie.android.data.local.NoteEntity
import com.snapaie.android.data.model.ExplainStyle
import com.snapaie.android.data.model.KnowledgeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryFilterTest {

    private val now = 1_700_000_000_000L
    private val hour = 60 * 60 * 1000L
    private val day = 24 * hour

    private fun scan(
        id: Long,
        title: String,
        createdAtMillis: Long,
        coreIdea: String = "",
        compression: Int = 0,
    ) = KnowledgeScan(
        id = id,
        createdAtMillis = createdAtMillis,
        mode = ExplainStyle.Concise,
        bookTitle = title,
        sourcePreview = "",
        sourceText = "",
        result = KnowledgeResult(coreIdea = coreIdea, compressionScore = compression),
        wordsIn = 0,
        wordsOut = 0,
    )

    @Test
    fun `empty query keeps everything`() {
        val scans = listOf(scan(1, "Meditations", now), scan(2, "Deep Work", now))

        val result = LibraryFilter.scans(scans, "", LibraryRange.All, LibrarySort.Newest, now)

        assertEquals(2, result.size)
    }

    @Test
    fun `search matches the AI output, not just the title`() {
        val scans = listOf(
            scan(1, "Meditations", now, coreIdea = "Control what is yours to control."),
            scan(2, "Deep Work", now, coreIdea = "Attention is a finite resource."),
        )

        val result = LibraryFilter.scans(scans, "attention", LibraryRange.All, LibrarySort.Newest, now)

        assertEquals(listOf(2L), result.map { it.id })
    }

    @Test
    fun `multiple terms narrow instead of widen`() {
        val scans = listOf(
            scan(1, "Deep Work", now, coreIdea = "Attention is a finite resource."),
            scan(2, "Shallow Work", now, coreIdea = "Attention fragments under context switching."),
        )

        val both = LibraryFilter.scans(scans, "attention finite", LibraryRange.All, LibrarySort.Newest, now)

        assertEquals(listOf(1L), both.map { it.id })
    }

    @Test
    fun `week range drops rows older than seven days`() {
        val scans = listOf(
            scan(1, "Recent", now - 2 * day),
            scan(2, "Old", now - 30 * day),
        )

        val result = LibraryFilter.scans(scans, "", LibraryRange.Week, LibrarySort.Newest, now)

        assertEquals(listOf(1L), result.map { it.id })
    }

    @Test
    fun `today range keeps only rows from the current calendar day`() {
        val scans = listOf(
            scan(1, "This morning", now - hour),
            scan(2, "Three days ago", now - 3 * day),
        )

        val result = LibraryFilter.scans(scans, "", LibraryRange.Today, LibrarySort.Newest, now)

        assertTrue(result.all { it.id == 1L })
    }

    @Test
    fun `best compression sort orders by score then recency`() {
        val scans = listOf(
            scan(1, "A", now - hour, compression = 40),
            scan(2, "B", now, compression = 90),
            scan(3, "C", now - 2 * hour, compression = 90),
        )

        val result = LibraryFilter.scans(scans, "", LibraryRange.All, LibrarySort.BestCompression, now)

        assertEquals(listOf(2L, 3L, 1L), result.map { it.id })
    }

    @Test
    fun `oldest sort reverses newest`() {
        val scans = listOf(scan(1, "A", now - day), scan(2, "B", now))

        val newest = LibraryFilter.scans(scans, "", LibraryRange.All, LibrarySort.Newest, now)
        val oldest = LibraryFilter.scans(scans, "", LibraryRange.All, LibrarySort.Oldest, now)

        assertEquals(newest.map { it.id }.reversed(), oldest.map { it.id })
    }

    @Test
    fun `notes filter on text and respect the range`() {
        val notes = listOf(
            NoteEntity(id = 1, text = "Ship the export flow", createdAtMillis = now - hour),
            NoteEntity(id = 2, text = "Buy milk", createdAtMillis = now - 40 * day),
        )

        assertEquals(
            listOf(1L),
            LibraryFilter.notes(notes, "export", LibraryRange.All, LibrarySort.Newest, now).map { it.id },
        )
        assertEquals(
            listOf(1L),
            LibraryFilter.notes(notes, "", LibraryRange.Week, LibrarySort.Newest, now).map { it.id },
        )
    }
}
