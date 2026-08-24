package com.snapaie.android.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The strike logic behind [VisionGuard], modelled without Android's SharedPreferences.
 *
 * The rule it encodes: a flag left raised across a launch means the process died inside a
 * vision call, because nothing else could have stopped it being lowered.
 */
class VisionGuardLogicTest {

    /** Mirrors VisionGuard against a plain map so the decision can be tested directly. */
    private class Fake {
        var inFlight = false
        var strikes = 0
        val allowed: Boolean get() = strikes < 2

        fun startup() {
            if (inFlight) {
                strikes++
                inFlight = false
            }
        }

        fun begin() { inFlight = true }
        fun end() { inFlight = false }
        /** The process vanishing: the flag is simply never lowered. */
        fun die() = Unit
    }

    @Test
    fun `a clean call leaves no strike`() {
        val guard = Fake()
        guard.begin()
        guard.end()
        guard.startup()
        assertEquals(0, guard.strikes)
        assertTrue(guard.allowed)
    }

    @Test
    fun `one crash is forgiven`() {
        // A single death can be a low-memory kill or a force-stop, not necessarily vision.
        val guard = Fake()
        guard.begin()
        guard.die()
        guard.startup()
        assertEquals(1, guard.strikes)
        assertTrue("one crash should not disable vision", guard.allowed)
    }

    @Test
    fun `twice is a pattern and vision is disabled`() {
        val guard = Fake()
        repeat(2) {
            guard.begin()
            guard.die()
            guard.startup()
        }
        assertEquals(2, guard.strikes)
        assertFalse("vision should be off after a repeat crash", guard.allowed)
    }

    @Test
    fun `startup with no call in flight changes nothing`() {
        val guard = Fake()
        repeat(5) { guard.startup() }
        assertEquals(0, guard.strikes)
        assertTrue(guard.allowed)
    }

    @Test
    fun `successful calls after a crash do not clear the strike`() {
        // The count is deliberately sticky: a device that crashed once is still suspect.
        val guard = Fake()
        guard.begin(); guard.die(); guard.startup()
        guard.begin(); guard.end(); guard.startup()
        assertEquals(1, guard.strikes)
    }
}
