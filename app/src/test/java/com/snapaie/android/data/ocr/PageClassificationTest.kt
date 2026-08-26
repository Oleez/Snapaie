package com.snapaie.android.data.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Deciding what happened when a photograph was read.
 *
 * This is the branch the paid tier hangs off. A text recogniser reads printed shapes and
 * cannot read handwriting at all — not badly, at all — and it fails by returning fragments
 * rather than nothing. So "it gave us something" is not the question; "is this a page" is.
 *
 * Getting it wrong is expensive in both directions. Call a real page unreadable and you
 * push someone toward a paid feature they did not need. Call recogniser noise a real page
 * and you condense garbage, confidently, which is what used to happen.
 */
class PageClassificationTest {

    private fun classify(text: String) = PageTextExtractor.classify(text)

    @Test
    fun `a clean printed page is read offline and costs nothing`() {
        val page = "One morning, when Gregor Samsa woke from troubled dreams, he found " +
            "himself transformed in his bed into a horrible vermin. He lay on his " +
            "armour-like back and saw his brown belly divided into stiff sections."
        assertEquals(TextSource.RECOGNISER, classify(page))
    }

    @Test
    fun `a page with nothing on it is not an upsell`() {
        // No text is not the same as unreadable text, and must not be sold as if it were.
        assertEquals(TextSource.NONE, classify(""))
        assertEquals(TextSource.NONE, classify("    \n  "))
    }

    @Test
    fun `handwriting shattered into fragments asks for the cloud`() {
        // What a recogniser actually returns from cursive: short, broken, wrong.
        assertEquals(TextSource.NEEDS_CLOUD, classify("Mo dy Th fr st tk th b ns ov r ni ht"))
    }

    @Test
    fun `a wall of symbols asks for the cloud`() {
        assertEquals(TextSource.NEEDS_CLOUD, classify("~|:;'^*#@%&+=<>{}[]()!?.,-_ ~|:;'^*#@%&+=<>"))
    }

    @Test
    fun `a couple of stray characters is not a page`() {
        assertEquals(TextSource.NEEDS_CLOUD, classify("a b"))
    }
}
