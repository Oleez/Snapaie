package com.snapaie.android.domain.condense

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Choosing sentences without a model.
 *
 * The properties here are the ones that decide whether the app needs a model at all: the
 * selection must be the author's own sentences, in the author's order, opening and closing
 * intact, near the requested length, and fast enough that nobody waits for it.
 */
class SentenceRankerTest {

    private val passage = Abridger.split(
        "Gregor Samsa woke from troubled dreams and found himself changed into a vermin. " +
            "He lay on his armour-like back and saw his brown belly divided into stiff sections. " +
            "The bedding could hardly cover it and was ready to slide off. " +
            "His many legs waved about helplessly as he looked at them. " +
            "What has happened to me, he thought. " +
            "It was not a dream. " +
            "His room lay peacefully between its four familiar walls. " +
            "A collection of textile samples lay spread out on the table. " +
            "Samsa was a travelling salesman and hated the work. " +
            "He decided he would sleep a little longer and forget the whole business.",
    )

    @Test
    fun `the opening and the ending always survive`() {
        listOf(10, 30, 60, 120).forEach { target ->
            val keep = SentenceRanker.choose(passage, target)
            assertTrue("target $target lost the opening", keep.contains(passage.first().index))
            assertTrue("target $target lost the ending", keep.contains(passage.last().index))
        }
    }

    @Test
    fun `sentences come back in the author's order`() {
        val keep = SentenceRanker.choose(passage, targetWords = 50)
        assertEquals("selection was reordered", keep.sorted(), keep)
    }

    @Test
    fun `a passage already short enough is left alone`() {
        val keep = SentenceRanker.choose(passage, targetWords = 10_000)
        assertEquals(passage.map { it.index }, keep)
    }

    @Test
    fun `a bigger budget keeps more of the passage`() {
        val small = SentenceRanker.choose(passage, targetWords = 40).size
        val large = SentenceRanker.choose(passage, targetWords = 120).size
        assertTrue("$large should keep more than $small", large > small)
    }

    @Test
    fun `the result lands near the budget rather than far under it`() {
        val keep = SentenceRanker.choose(passage, targetWords = 80)
        val words = keep.sumOf { index -> passage.first { it.index == index }.words }
        assertTrue("kept only $words words of an 80-word budget", words >= 40)
    }

    @Test
    fun `every kept sentence is one the author wrote`() {
        val keep = SentenceRanker.choose(passage, targetWords = 60)
        keep.forEach { index ->
            assertTrue("index $index is not a real sentence", passage.any { it.index == index })
        }
    }

    @Test
    fun `an empty passage chooses nothing rather than failing`() {
        assertEquals(emptyList<Int>(), SentenceRanker.choose(emptyList(), targetWords = 50))
    }

    @Test
    fun `choosing is fast enough that nobody waits for it`() {
        // The whole argument for doing this locally. A model answers this same question in
        // minutes; if this took even a second the trade would not be worth making.
        val long = Abridger.split((0 until 400).joinToString(" ") {
            "Sentence number $it discusses a subject of moderate and passing interest."
        })
        val started = System.nanoTime()
        SentenceRanker.choose(long, targetWords = 900)
        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue("selecting from ${long.size} sentences took ${millis}ms", millis < 2_000)
    }
}
