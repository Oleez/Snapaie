package com.snapaie.android.domain.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning "read this page" into the text of the page.
 *
 * This is the seam where a document can be silently replaced by a description of itself. A
 * model asked to transcribe sometimes answers about the page instead of from it, and once
 * that text is accepted nothing downstream can tell — a description condenses just as
 * happily as a transcription, and the reader gets a confident summary of a page nobody read.
 */
class TranscriptionTest {

    private val realPage = "Monday. The frost took the beans overnight and the top field " +
        "is bare. Walked to Hallow Bridge to ask about the mare."

    @Test
    fun `a real transcription is kept as written`() {
        assertEquals(realPage, Transcription.clean(realPage))
    }

    @Test
    fun `a description of the page is refused`() {
        listOf(
            "This appears to be a handwritten note about farming.",
            "The image shows a page of cursive handwriting on lined paper.",
            "I can see a handwritten diary entry, though parts are hard to read.",
            "I'm unable to read this handwriting clearly.",
            "This is a page from what looks like a personal journal.",
        ).forEach {
            assertEquals("accepted a description: $it", "", Transcription.clean(it))
        }
    }

    @Test
    fun `a polite preamble is stripped rather than losing the page`() {
        val kept = Transcription.clean("Here is the text: $realPage")
        assertTrue("the page was thrown away with the preamble", kept.contains("frost took the beans"))
        assertTrue("the preamble survived", !kept.startsWith("Here is the text"))
    }

    @Test
    fun `runtime noise never becomes a page`() {
        assertEquals("", Transcription.clean("LiteRT-LM stream error: Status Code: 3."))
    }

    @Test
    fun `a page of nothing but unclear markers is not a page`() {
        assertEquals("", Transcription.clean("[unclear] [unclear] [unclear]"))
    }

    @Test
    fun `silence is silence`() {
        assertEquals("", Transcription.clean(""))
        assertEquals("", Transcription.clean("   \n  "))
    }
}
