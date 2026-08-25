package com.snapaie.android.domain.output

import org.junit.Assert.assertTrue
import org.junit.Test

class FrontMatterTest {

    private fun text(blocks: List<ContentBlock>) = blocks.joinToString(" ") {
        when (it) {
            is ContentBlock.Heading -> it.text
            is ContentBlock.Paragraph -> it.text
            is ContentBlock.Image -> ""
        }
    }

    @Test
    fun `it names itself an abridgement rather than a summary`() {
        val front = FrontMatter.build("The Metamorphosis", "Franz Kafka", 21_000, 13_402, 3)
        val body = text(front)
        assertTrue(body.contains("Abridged Reader's Edition"))
        assertTrue(body.contains("not a summary"))
    }

    @Test
    fun `it states the promise that the words are the author's`() {
        val body = text(FrontMatter.build("A Book", "An Author", 20_000, 10_000, 4))
        assertTrue(body.contains("exactly as written"))
        assertTrue(body.contains("Nothing has been reworded"))
    }

    @Test
    fun `it reports length and reading time`() {
        val body = text(FrontMatter.build("A Book", "An Author", 21_000, 13_402, 3))
        assertTrue("word count missing: $body", body.contains("13,402 words"))
        assertTrue("reading time missing: $body", body.contains("minutes"))
        assertTrue("proportion missing: $body", body.contains("63%"))
    }

    @Test
    fun `reading time gives a range, fastest first`() {
        val line = FrontMatter.readingTimeLine(13_402)
        val range = Regex("""(\d+)–(\d+) minutes""").find(line)!!
        val fast = range.groupValues[1].toInt()
        val slow = range.groupValues[2].toInt()
        assertTrue("range is inverted: $line", fast < slow)
    }

    @Test
    fun `a missing author does not leave an empty line`() {
        val front = FrontMatter.build("A Book", "", 1_000, 500, 1)
        assertTrue(front.filterIsInstance<ContentBlock.Paragraph>().none { it.text.isBlank() })
    }

    @Test
    fun `degenerate counts do not produce nonsense`() {
        val body = text(FrontMatter.build("A Book", "", 0, 0, 0))
        assertTrue(body.contains("not a summary"))
        assertTrue("claimed a percentage it cannot know: $body", !body.contains("0%"))
    }

    @Test
    fun `front matter never collides with a real chapter`() {
        val ids = FrontMatter.build("A Book", "A", 100, 50, 1)
            .filterIsInstance<ContentBlock.Heading>().map { it.chapterId }
        assertTrue("front matter used a positive id: $ids", ids.all { it < 0 })
    }
}
