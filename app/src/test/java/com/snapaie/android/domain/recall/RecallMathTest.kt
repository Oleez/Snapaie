package com.snapaie.android.domain.recall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Parity checks against the extension's JS formulas (popup.js Forge Recall). */
class SpacedRepetitionSchedulerTest {

    @Test
    fun `schedule follows 1-3-7-14-30 by review count at low strength`() {
        val strength = 10
        assertEquals(1, SpacedRepetitionScheduler.intervalDays(0, strength))
        assertEquals(3, SpacedRepetitionScheduler.intervalDays(1, strength))
        assertEquals(7, SpacedRepetitionScheduler.intervalDays(2, strength))
        assertEquals(14, SpacedRepetitionScheduler.intervalDays(3, strength))
        assertEquals(30, SpacedRepetitionScheduler.intervalDays(4, strength))
    }

    @Test
    fun `review count is clamped to the last schedule slot`() {
        assertEquals(30, SpacedRepetitionScheduler.intervalDays(99, 10))
    }

    @Test
    fun `strength raises the interval floor`() {
        assertEquals(3, SpacedRepetitionScheduler.intervalDays(0, 35))
        assertEquals(7, SpacedRepetitionScheduler.intervalDays(0, 58))
        assertEquals(14, SpacedRepetitionScheduler.intervalDays(0, 78))
        assertEquals(30, SpacedRepetitionScheduler.intervalDays(0, 92))
    }

    @Test
    fun `strength floor never shortens an already longer interval`() {
        assertEquals(30, SpacedRepetitionScheduler.intervalDays(4, 35))
    }

    @Test
    fun `next due is now plus the interval`() {
        val now = 1_000_000L
        val due = SpacedRepetitionScheduler.nextDueAt(0, 10, now)
        assertEquals(now + TimeUnit.DAYS.toMillis(1), due)
    }

    @Test
    fun `due states classify correctly`() {
        val now = 1_000_000_000L
        assertTrue(SpacedRepetitionScheduler.isDueNow(now - 1, now))
        assertFalse(SpacedRepetitionScheduler.isDueNow(now + 1, now))
        assertTrue(SpacedRepetitionScheduler.isDueSoon(now + TimeUnit.HOURS.toMillis(12), now))
        assertFalse(SpacedRepetitionScheduler.isDueSoon(now + TimeUnit.DAYS.toMillis(3), now))
    }

    @Test
    fun `strength delta rewards perfect and punishes poor sessions`() {
        assertEquals(12, SpacedRepetitionScheduler.strengthDelta(10, 10))
        assertEquals(8, SpacedRepetitionScheduler.strengthDelta(6, 10))
        assertEquals(-5, SpacedRepetitionScheduler.strengthDelta(2, 10))
        assertEquals(0, SpacedRepetitionScheduler.strengthDelta(0, 0))
    }
}

class XpLedgerTest {

    @Test
    fun `rapid fire xp matches the extension formula`() {
        assertEquals(28 + 7 * 6, XpLedger.rapidFireXp(score = 7, completed = true))
        assertEquals(7 * 3, XpLedger.rapidFireXp(score = 7, completed = false))
        assertEquals(28, XpLedger.rapidFireXp(score = 0, completed = true))
    }

    @Test
    fun `survival xp matches the extension formula`() {
        assertEquals(45 * 2 + 6 * 10, XpLedger.survivalXp(elapsedSec = 45, correctStreak = 6))
    }

    @Test
    fun `levels use 500 xp per level`() {
        assertEquals(1, XpLedger.levelFor(0))
        assertEquals(1, XpLedger.levelFor(499))
        assertEquals(2, XpLedger.levelFor(500))
        assertEquals(5, XpLedger.levelFor(2000))
    }

    @Test
    fun `level progress is the fraction within the current level`() {
        assertEquals(0f, XpLedger.levelProgress(500), 0.001f)
        assertEquals(0.5f, XpLedger.levelProgress(750), 0.001f)
    }

    @Test
    fun `feynman xp is half the score`() {
        assertEquals(50, XpLedger.feynmanXp(100))
        assertEquals(35, XpLedger.feynmanXp(71))
        assertEquals(0, XpLedger.feynmanXp(-20))
    }

    @Test
    fun `cloze stays locked below level three`() {
        assertFalse(XpLedger.rollCloze(level = 2, random = kotlin.random.Random(1)))
    }
}
