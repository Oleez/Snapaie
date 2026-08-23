package com.snapaie.android.domain.condense

import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetGovernorTest {

    /** A 500-page novel condensed to roughly 150 pages. */
    private val sourceWords = 150_000
    private val targetWords = 50_000

    @Test
    fun `converges on the target despite a model that consistently overshoots`() {
        val governor = BudgetGovernor(targetWords, sourceWords)
        val random = Random(11)
        var consumed = 0
        while (consumed < sourceWords) {
            val beatWords = minOf(900, sourceWords - consumed)
            val budget = governor.budgetFor(beatWords)
            // The model runs 25% long, every single time.
            val produced = (budget * 1.25f).toInt()
            governor.record(produced, beatWords)
            consumed += beatWords
        }
        val drift = abs(governor.producedWords - targetWords).toFloat() / targetWords
        assertTrue("drifted ${(drift * 100).toInt()}% from target", drift < 0.08f)
    }

    @Test
    fun `converges when the model consistently undershoots`() {
        val governor = BudgetGovernor(targetWords, sourceWords)
        var consumed = 0
        while (consumed < sourceWords) {
            val beatWords = minOf(900, sourceWords - consumed)
            val produced = (governor.budgetFor(beatWords) * 0.8f).toInt()
            governor.record(produced, beatWords)
            consumed += beatWords
        }
        val drift = abs(governor.producedWords - targetWords).toFloat() / targetWords
        assertTrue("drifted ${(drift * 100).toInt()}% from target", drift < 0.08f)
    }

    @Test
    fun `no beat is ever starved below the floor`() {
        val governor = BudgetGovernor(targetWords, sourceWords)
        val random = Random(3)
        var consumed = 0
        var minBudget = Int.MAX_VALUE
        while (consumed < sourceWords) {
            val beatWords = minOf(200 + random.nextInt(1_400), sourceWords - consumed)
            val budget = governor.budgetFor(beatWords)
            minBudget = minOf(minBudget, budget)
            // A pathological run that blows the whole budget in the first few beats.
            governor.record(budget * 3, beatWords)
            consumed += beatWords
        }
        assertTrue(
            "a beat was starved to $minBudget words",
            minBudget >= BudgetGovernor.MIN_BEAT_WORDS,
        )
    }

    @Test
    fun `a beat is never asked to produce more words than it was given`() {
        val governor = BudgetGovernor(totalTargetWords = 100_000, totalSourceWords = 10_000)
        assertTrue(governor.budgetFor(500) <= 500)
    }

    @Test
    fun `resuming mid-book reproduces the same budget as an uninterrupted run`() {
        // This is what a process death has to survive: the governor is reconstructed from
        // the two totals the database already stores.
        val whole = BudgetGovernor(targetWords, sourceWords)
        repeat(40) { whole.record(280, 900) }
        val afterCrash = BudgetGovernor(
            totalTargetWords = targetWords,
            totalSourceWords = sourceWords,
            producedWords = 40 * 280,
            consumedSourceWords = 40 * 900,
        )
        assertEquals(whole.budgetFor(900), afterCrash.budgetFor(900))
        assertEquals(whole.remainingBudget, afterCrash.remainingBudget)
    }

    @Test
    fun `degenerate inputs do not divide by zero`() {
        assertEquals(0, BudgetGovernor(1_000, 0).budgetFor(0))
        assertTrue(BudgetGovernor(0, 0).budgetFor(500) >= BudgetGovernor.MIN_BEAT_WORDS)
        assertTrue(BudgetGovernor(1_000, 5_000).budgetFor(-5) == 0)
    }

    @Test
    fun `the ladder triggers only for aggressive targets`() {
        assertTrue(CondenseTarget.needsLadder(targetWords = 15_000, sourceWords = 150_000))
        assertFalse(CondenseTarget.needsLadder(targetWords = 45_000, sourceWords = 150_000))
        assertFalse(CondenseTarget.needsLadder(targetWords = 1_000, sourceWords = 0))
    }

    @Test
    fun `page and percent targets convert sensibly`() {
        assertEquals(150 * CondenseTarget.WORDS_PER_PAGE, CondenseTarget.wordsForPages(150))
        assertEquals(45_000, CondenseTarget.wordsForPercent(150_000, 30))
        // Never ask for less than a page of output, whatever the percentage.
        assertTrue(CondenseTarget.wordsForPercent(100, 1) >= CondenseTarget.WORDS_PER_PAGE)
    }
}
