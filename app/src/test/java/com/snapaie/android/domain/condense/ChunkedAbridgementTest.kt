package com.snapaie.android.domain.condense

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Passages larger than the model's context window.
 *
 * This is what the window limit cost before: a page longer than the context had its tail
 * truncated, and the reader was never told. Walking the passage a run at a time removes
 * the limit entirely, so the tests that matter are about the tail surviving and the runs
 * joining back together in the right order.
 */
class ChunkedAbridgementTest {

    /** Sentences of a known length, so a chunk size maps to a predictable run count. */
    private fun sentences(count: Int): List<Abridger.Sentence> =
        (0 until count).map { Abridger.Sentence(it, "Sentence number $it is here.", words = 5) }

    private suspend fun keepAll(numbered: String, count: Int, target: Int): String =
        (0 until count).joinToString(", ")

    @Test
    fun `a passage larger than the window is split into several runs`() {
        val runs = ChunkedAbridgement.chunk(sentences(40), maxChars = 200)
        assertTrue("expected several runs, got ${runs.size}", runs.size > 1)
        assertEquals("every sentence must belong to exactly one run", 40, runs.sumOf { it.size })
    }

    @Test
    fun `the runs tile the passage in order with nothing lost`() {
        val all = sentences(37)
        val flattened = ChunkedAbridgement.chunk(all, maxChars = 150).flatten()
        assertEquals(all.map { it.index }, flattened.map { it.index })
    }

    @Test
    fun `the end of a long passage still reaches the model`() = runTest {
        // The failure this replaces: everything past the window was silently dropped.
        val all = sentences(60)
        val seen = mutableListOf<Int>()
        ChunkedAbridgement.keepIndices(all, targetWords = 150, maxChunkChars = 200) { numbered, count, _ ->
            seen += count
            keepAll(numbered, count, 0)
        }
        assertEquals("not every sentence was offered to the model", 60, seen.sum())
    }

    @Test
    fun `indices come back global and in the book's order`() = runTest {
        val all = sentences(50)
        val walk = ChunkedAbridgement.keepIndices(all, targetWords = 60, maxChunkChars = 200) { _, count, _ ->
            // Keep the first sentence of every run, numbered locally.
            "0"
        }
        assertEquals("indices must be sorted", walk.keep.sorted(), walk.keep)
        assertTrue("indices must be global", walk.keep.all { it in 0 until 50 })
        assertTrue("later runs never contributed", walk.keep.any { it > 10 })
    }

    @Test
    fun `a run the model cannot answer is reported, not hidden`() = runTest {
        val all = sentences(40)
        val walk = ChunkedAbridgement.keepIndices(all, targetWords = 60, maxChunkChars = 200) { _, _, _ -> null }
        assertTrue("a local choice must be reported as a fallback", walk.usedFallback)
        assertEquals("every run fell back", walk.runs, walk.runsFallenBack)
        assertTrue("a fallback must still produce sentences", walk.keep.isNotEmpty())
    }

    @Test
    fun `one bad run costs that run and not the passage`() = runTest {
        val all = sentences(40)
        var call = 0
        val walk = ChunkedAbridgement.keepIndices(all, targetWords = 100, maxChunkChars = 200) { numbered, count, _ ->
            call++
            if (call == 1) null else keepAll(numbered, count, 0)
        }
        assertTrue("more than one run should have been walked", walk.runs > 1)
        assertEquals("only the failing run should fall back", 1, walk.runsFallenBack)
    }

    @Test
    fun `the budget is shared across the runs rather than spent on each`() = runTest {
        val all = sentences(40)
        val targets = mutableListOf<Int>()
        ChunkedAbridgement.keepIndices(all, targetWords = 100, maxChunkChars = 200) { _, count, target ->
            targets += target
            keepAll("", count, target)
        }
        assertEquals("the runs must divide the budget, not each take it", 100, targets.sum())
    }
}
