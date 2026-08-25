package com.snapaie.android.domain.condense

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Abridgement is deletion, and these pin the property that makes it worth doing: every
 * word that survives is the author's, byte for byte.
 */
class AbridgerTest {

    private val kafka = "'O God,' he thought, 'what a demanding job I've chosen! Day in, day " +
        "out on the road. The stresses of trade are much greater than the work going on at " +
        "head office. To hell with it all!'"

    @Test
    fun `sentences are split on real boundaries`() {
        val sentences = Abridger.split(kafka)
        assertEquals(4, sentences.size)
        assertTrue(sentences[0].text.endsWith("I've chosen!"))
        assertTrue(sentences.last().text.contains("To hell with it all!"))
    }

    @Test
    fun `an abbreviation does not end a sentence`() {
        // "Mr. Samsa" must stay whole, or the abridger is offered fragments.
        val text = "Mr. Samsa threw the bedspread over his shoulders. Mrs. Samsa came out."
        assertEquals(2, Abridger.split(text).size)
    }

    @Test
    fun `an initial does not end a sentence`() {
        assertEquals(1, Abridger.split("The text was translated by I. Johnston for students.").size)
    }

    @Test
    fun `kept sentences come back verbatim`() {
        val sentences = Abridger.split(kafka)
        val result = Abridger.assemble(sentences, listOf(0, 3))
        assertEquals(
            "'O God,' he thought, 'what a demanding job I've chosen! To hell with it all!'",
            result,
        )
        // Every retained fragment is present in the source exactly as written.
        listOf("what a demanding job I've chosen!", "To hell with it all!").forEach {
            assertTrue("'$it' was altered", kafka.contains(it) && result.contains(it))
        }
    }

    @Test
    fun `order is always the original order`() {
        val sentences = Abridger.split(kafka)
        val shuffled = Abridger.assemble(sentences, listOf(3, 0, 2))
        val ordered = Abridger.assemble(sentences, listOf(0, 2, 3))
        assertEquals(ordered, shuffled)
    }

    @Test
    fun `a messy reply still yields a keep list`() {
        listOf(
            "0, 1, 4",
            "[0,1,4]",
            "Keep: 0\n1\n4",
            "Sure! I would keep sentences 0, 1 and 4.",
        ).forEach { reply ->
            assertEquals("failed on: $reply", listOf(0, 1, 4), Abridger.parseKeepList(reply, 5))
        }
    }

    @Test
    fun `indices outside the passage are discarded`() {
        assertEquals(listOf(0, 2), Abridger.parseKeepList("0, 2, 9, 41", 3))
        assertEquals(emptyList<Int>(), Abridger.parseKeepList("nothing here", 3))
    }

    @Test
    fun `the local chooser keeps the opening and the ending`() {
        val long = (1..12).joinToString(" ") { "Sentence number $it carries some weight here." }
        val sentences = Abridger.split(long)
        val keep = Abridger.chooseLocally(sentences, targetWords = 24)
        assertTrue("dropped the opening", keep.contains(0))
        assertTrue("dropped the ending", keep.contains(sentences.lastIndex))
        val words = Abridger.countWords(Abridger.assemble(sentences, keep))
        assertTrue("kept $words words against a target of 24", words <= 30)
    }

    @Test
    fun `a passage already short enough is kept whole`() {
        val sentences = Abridger.split(kafka)
        assertEquals(sentences.map { it.index }, Abridger.chooseLocally(sentences, targetWords = 500))
    }

    @Test
    fun `degenerate input does not throw`() {
        listOf("", "   ", "one", "...", "?!").forEach { text ->
            val sentences = Abridger.split(text)
            Abridger.assemble(sentences, Abridger.chooseLocally(sentences, 10))
        }
    }
}
