package com.snapaie.android.domain.condense

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PassageSlicerTest {

    @Test
    fun `a short passage is one slice`() {
        val text = "Alice met Bob. Then they left."
        assertEquals(listOf(text), PassageSlicer.slices(text, maxChars = 500))
    }

    @Test
    fun `a long passage becomes ordered slices that rebuild the original`() {
        val paragraph = "Alice met Bob in Paris. The rain kept falling on the street.\n\n"
        val text = paragraph.repeat(8)
        val slices = PassageSlicer.slices(text, maxChars = paragraph.length + 10)

        assertTrue("expected more than one slice, got ${slices.size}", slices.size >= 2)
        slices.forEach { slice ->
            assertTrue("slice is ${slice.length} characters", slice.length <= paragraph.length + 10)
        }
        assertEquals(text, slices.joinToString(""))

        var cursor = 0
        slices.forEach { slice ->
            assertEquals(cursor, text.indexOf(slice, cursor))
            cursor += slice.length
        }
    }

    @Test
    fun `a slice does not end in the middle of a word when a space is available`() {
        val text = "one two three four five six seven eight nine ten"
        val slices = PassageSlicer.slices(text, maxChars = 20)
        assertTrue(slices.size >= 2)
        slices.dropLast(1).forEach { slice ->
            assertTrue("'$slice' ends mid-word", slice.last().isWhitespace() || slice.endsWith(" "))
        }
        assertEquals(text, slices.joinToString(""))
    }
}
