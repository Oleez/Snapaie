package com.snapaie.android.domain.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.random.Random
import org.junit.Test

/**
 * The tiling invariant is the whole point of this class, so it is asserted as a reusable
 * check and run against every shape of input the ingesters can produce.
 */
class SegmenterTest {

    private fun assertTiles(text: String, beats: List<SourceBeat>) {
        assertTrue("no beats produced for ${text.length} chars", beats.isNotEmpty())
        assertEquals("first beat must start at 0", 0, beats.first().startChar)
        assertEquals("last beat must end at the end of the text", text.length, beats.last().endChar)
        beats.zipWithNext { a, b ->
            assertEquals("gap or overlap between beat ${a.orderIndex} and ${b.orderIndex}", a.endChar, b.startChar)
        }
        beats.forEach { assertTrue("empty beat ${it.orderIndex}", it.endChar > it.startChar) }
        assertEquals(
            "beat order indices must be dense and ascending",
            beats.indices.toList(),
            beats.map { it.orderIndex },
        )
        // No word is cut in half: reassembling the ranges reproduces the text exactly.
        assertEquals(text, beats.joinToString("") { text.substring(it.startChar, it.endChar) })
        assertEquals(
            "beat word counts must sum to the book's word count",
            Segmenter.countWords(text),
            beats.sumOf { it.words },
        )
    }

    private fun segment(text: String, target: Int = Segmenter.DEFAULT_BEAT_WORDS): List<SourceBeat> {
        val chapters = Segmenter.detectChapters(text)
        return Segmenter.segmentBeats(text, chapters, target)
    }

    private fun prose(paragraphs: Int, wordsEach: Int, seed: Int = 7): String {
        val random = Random(seed)
        val vocabulary = listOf(
            "Mira", "walked", "through", "the", "harbour", "gate", "and", "counted",
            "lanterns", "while", "Deven", "waited", "beneath", "cold", "stone", "arches",
        )
        return (1..paragraphs).joinToString("\n\n") {
            (1..wordsEach).joinToString(" ") { vocabulary[random.nextInt(vocabulary.size)] } + "."
        }
    }

    @Test
    fun `beats tile a plain run of prose`() {
        val text = prose(paragraphs = 40, wordsEach = 60)
        assertTiles(text, segment(text))
    }

    @Test
    fun `beats tile text with chapter headings`() {
        val text = buildString {
            append("A short foreword that belongs to nobody.\n\n")
            (1..5).forEach { chapter ->
                append("Chapter $chapter\n\n")
                append(prose(paragraphs = 12, wordsEach = 70, seed = chapter))
                append("\n\n")
            }
        }
        val chapters = Segmenter.detectChapters(text)
        val beats = Segmenter.segmentBeats(text, chapters)

        assertEquals("front matter plus five chapters", 6, chapters.size)
        assertEquals("Front matter", chapters.first().title)
        assertTiles(text, beats)
        // Chapters tile too, and no beat straddles one.
        assertEquals(0, chapters.first().startChar)
        assertEquals(text.length, chapters.last().endChar)
        chapters.zipWithNext { a, b -> assertEquals(a.endChar, b.startChar) }
        beats.forEach { beat ->
            val chapter = chapters[beat.chapterIndex]
            assertTrue(
                "beat ${beat.orderIndex} escapes chapter ${beat.chapterIndex}",
                beat.startChar >= chapter.startChar && beat.endChar <= chapter.endChar,
            )
        }
    }

    @Test
    fun `scene breaks are preferred cut points but never lose text`() {
        val text = buildString {
            append(prose(paragraphs = 10, wordsEach = 80, seed = 1))
            append("\n\n* * *\n\n")
            append(prose(paragraphs = 10, wordsEach = 80, seed = 2))
            append("\n\n* * *\n\n")
            append(prose(paragraphs = 10, wordsEach = 80, seed = 3))
        }
        assertTiles(text, segment(text))
    }

    @Test
    fun `a paragraph longer than the budget still becomes one beat rather than being split`() {
        val text = prose(paragraphs = 3, wordsEach = 2_000)
        val beats = segment(text, target = 300)
        assertTiles(text, beats)
        assertTrue("an oversized paragraph must not be split", beats.all { it.words >= 300 })
    }

    @Test
    fun `hints from a real outline win over the heuristics`() {
        val text = "Front.\n\nOPENING\n\n" + prose(6, 40) + "\n\nCLOSING\n\n" + prose(6, 40)
        val hints = listOf(
            ChapterHint("Act One", text.indexOf("OPENING")),
            ChapterHint("Act Two", text.indexOf("CLOSING")),
        )
        val chapters = Segmenter.detectChapters(text, hints)
        assertEquals(listOf("Front matter", "Act One", "Act Two"), chapters.map { it.title })
        assertTiles(text, Segmenter.segmentBeats(text, chapters))
    }

    @Test
    fun `degenerate inputs still tile`() {
        listOf(
            "one",
            "   \n\n   \n\n   ",
            "* * *",
            "Chapter 1\n\nChapter 2\n\nChapter 3",
            "word ".repeat(5_000),
        ).forEach { text ->
            assertTiles(text, segment(text, target = 200))
        }
    }

    @Test
    fun `empty text produces no chapters and no beats`() {
        assertTrue(Segmenter.detectChapters("").isEmpty())
        assertTrue(Segmenter.segmentBeats("", emptyList()).isEmpty())
    }

    @Test
    fun `a single weak heading is ignored rather than fragmenting the book`() {
        // One Title Case line in the middle of prose is far more likely to be a running
        // head than a real chapter, so the whole text stays one chapter.
        val text = prose(8, 50) + "\n\nThe Long Way Home\n\n" + prose(8, 50)
        val chapters = Segmenter.detectChapters(text)
        assertEquals(1, chapters.size)
        assertTiles(text, Segmenter.segmentBeats(text, chapters))
    }

    @Test
    fun `beats land near the requested budget`() {
        val text = prose(paragraphs = 120, wordsEach = 40)
        val beats = segment(text, target = 400)
        // The budget is a floor a whole paragraph may overshoot, never a hard cut, so
        // every beat but the last should sit in a tight band above it.
        beats.dropLast(1).forEach { beat ->
            assertTrue("beat ${beat.orderIndex} of ${beat.words} words is off budget", beat.words in 400..460)
        }
    }
}
