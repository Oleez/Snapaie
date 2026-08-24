package com.snapaie.android.domain.condense

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The last rung of the condensation ladder, and the one that has to work with no model, no
 * network and no luck. If this returns nothing, a snap shows an empty panel — which is the
 * exact failure that made the feature look broken.
 */
class ExtractiveFallbackTest {

    private val page = """
        Mira crossed the harbour bridge before dawn, counting the lanterns as she went. The
        fog had not lifted and the water below was invisible, though she could hear it
        moving against the pilings with a sound like someone breathing in their sleep.

        Deven was waiting at the far end with the ledger tucked under his coat. He did not
        look at her when she reached him, which told her most of what she needed to know
        before he opened his mouth at all.

        "They moved the shipment," he said. "Last night, before the tide turned. Kestrel
        knows about the manifest, and if Kestrel knows then half the customs house knows
        by now as well, which means we have until morning at the outside."
    """.trimIndent()

    @Test
    fun `a real page always produces something`() {
        val out = ExtractiveCondenser.shorten(page, budgetWords = 60)
        assertTrue("produced nothing", out.isNotBlank())
        assertTrue("suspiciously short: '$out'", out.length > 100)
    }

    @Test
    fun `it is shorter than the page it came from`() {
        val out = ExtractiveCondenser.shorten(page, budgetWords = 60)
        assertTrue("not actually shorter", out.length < page.length)
    }

    @Test
    fun `paragraph order is preserved`() {
        val out = ExtractiveCondenser.shorten(page, budgetWords = 90)
        val mira = out.indexOf("Mira")
        val deven = out.indexOf("Deven")
        assertTrue("Mira missing", mira >= 0)
        assertTrue("Deven missing", deven >= 0)
        assertTrue("order was not kept", mira < deven)
    }

    @Test
    fun `names survive`() {
        val out = ExtractiveCondenser.shorten(page, budgetWords = 90)
        listOf("Mira", "Deven").forEach {
            assertTrue("$it was dropped", out.contains(it))
        }
    }

    @Test
    fun `a single unbroken paragraph still shortens`() {
        val single = "Mira crossed the bridge. " .repeat(40)
        val out = ExtractiveCondenser.shorten(single, budgetWords = 30)
        assertTrue(out.isNotBlank())
        assertTrue(out.length < single.length)
    }

    @Test
    fun `degenerate input does not throw`() {
        listOf("", "   ", "One.", "\n\n\n").forEach { input ->
            val out = ExtractiveCondenser.shorten(input, budgetWords = 60)
            assertEquals(input.trim(), out.trim().ifEmpty { input.trim() })
        }
    }
}
