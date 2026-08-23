package com.snapaie.android.domain.output

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookLayoutEngineTest {

    /** Monospace-ish stand-in: every glyph is 0.5em, bold 0.55em. Deterministic and enough
     *  to exercise wrapping and pagination without a PDF font. */
    private val measure = TextWidth { text, sizePt, bold ->
        text.length * sizePt * if (bold) 0.55f else 0.5f
    }

    private val spec = PageSpec.SIX_BY_NINE
    private val engine = BookLayoutEngine(spec, measure)

    private fun body(chapters: Int, paragraphsEach: Int): List<ContentBlock> = buildList {
        (1..chapters).forEach { index ->
            add(ContentBlock.Heading(chapterId = index.toLong(), text = "Chapter $index", sourcePage = index * 20))
            repeat(paragraphsEach) {
                add(ContentBlock.Paragraph("Mira crossed the harbour bridge before dawn. ".repeat(6)))
            }
        }
    }

    @Test
    fun `every chapter starts on its own page`() {
        val layout = engine.layout(body(chapters = 5, paragraphsEach = 3))
        val startPages = layout.pages.filter { it.chapterStarts.isNotEmpty() }
        assertEquals(5, startPages.size)
        startPages.forEach { assertEquals(1, it.chapterStarts.size) }
    }

    @Test
    fun `the contents lists every chapter with its real output page`() {
        val layout = engine.layout(body(chapters = 8, paragraphsEach = 4))
        assertEquals(8, layout.toc.size)

        layout.toc.forEach { entry ->
            val page = layout.pages.firstOrNull { it.index + 1 == entry.outputPage }
            assertNotNull("no page ${entry.outputPage} for ${entry.title}", page)
            assertTrue(
                "${entry.title} says page ${entry.outputPage} but does not start there",
                page!!.chapterStarts.contains(entry.chapterId),
            )
        }
    }

    @Test
    fun `page numbers stay correct when the contents itself spills onto extra pages`() {
        // The fixed point: 60 chapters need several contents pages, and adding them shifts
        // every body page number that the contents is listing.
        val layout = engine.layout(body(chapters = 60, paragraphsEach = 2))
        assertTrue("contents should need more than one page", layout.tocPageCount > 1)
        assertEquals(layout.tocPageCount, layout.pages.count { it.index < layout.tocPageCount })

        layout.toc.forEach { entry ->
            assertTrue(
                "${entry.title} points into the contents at page ${entry.outputPage}",
                entry.outputPage > layout.tocPageCount,
            )
            val page = layout.pages.first { it.index + 1 == entry.outputPage }
            assertTrue(page.chapterStarts.contains(entry.chapterId))
        }
    }

    @Test
    fun `pages are numbered densely from zero with no holes`() {
        val layout = engine.layout(body(chapters = 12, paragraphsEach = 5))
        assertEquals(layout.pages.indices.toList(), layout.pages.map { it.index })
    }

    @Test
    fun `the original page number is carried through for cross-checking`() {
        val layout = engine.layout(body(chapters = 4, paragraphsEach = 2))
        assertEquals(listOf(20, 40, 60, 80), layout.toc.map { it.sourcePage })
    }

    @Test
    fun `no line overflows the text column`() {
        val layout = engine.layout(body(chapters = 3, paragraphsEach = 6))
        layout.pages.flatMap { it.lines }.forEach { line ->
            assertTrue(
                "line overflows: ${line.text}",
                measure.widthOf(line.text, line.sizePt, line.bold) <= spec.textWidthPt + 0.01f,
            )
        }
    }

    @Test
    fun `an unbreakable run is split rather than allowed to overflow`() {
        val long = "x".repeat(400)
        val lines = engine.wrap(long, spec.textWidthPt, spec.bodySizePt, bold = false)
        assertTrue(lines.size > 1)
        assertEquals(long, lines.joinToString(""))
        lines.forEach {
            assertTrue(measure.widthOf(it, spec.bodySizePt, false) <= spec.textWidthPt + 0.01f)
        }
    }

    @Test
    fun `no text is ever dropped by wrapping`() {
        val text = "Mira crossed the harbour bridge before dawn, counting lanterns."
        val joined = engine.wrap(text, 120f, spec.bodySizePt, bold = false).joinToString(" ")
        assertEquals(text, joined)
    }

    @Test
    fun `an image that will not fit moves whole to the next page`() {
        val blocks = listOf(
            ContentBlock.Heading(1L, "Chapter One"),
            ContentBlock.Paragraph("Filler. ".repeat(400)),
            ContentBlock.Image("/tmp/plate.jpg", widthPx = 1200, heightPx = 1600, caption = "The harbour at dawn"),
            ContentBlock.Paragraph("After the plate."),
        )
        val layout = engine.layout(blocks)
        val placed = layout.pages.flatMap { it.images }.single()
        assertTrue("image is wider than the column", placed.widthPt <= spec.textWidthPt + 0.01f)
        assertTrue(
            "image runs off the bottom of the page",
            placed.yPt + placed.heightPt <= spec.heightPt - spec.marginPt + 0.01f,
        )
    }

    @Test
    fun `a book with no chapters still lays out`() {
        val layout = engine.layout(listOf(ContentBlock.Paragraph("Only this.")))
        assertTrue(layout.pages.isNotEmpty())
        assertTrue(layout.toc.isEmpty())
    }
}
