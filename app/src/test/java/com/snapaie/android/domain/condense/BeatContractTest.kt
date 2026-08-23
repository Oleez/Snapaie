package com.snapaie.android.domain.condense

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BeatContractTest {

    private val source = """
        Mira crossed the harbour bridge before dawn, counting the lanterns as she went.
        Deven was waiting at the far end with the ledger under his coat. He did not look
        at her. "They moved the shipment," he said. "Kestrel knows."
    """.trimIndent()

    private fun prose(words: Int, seed: String = "Mira walked on") =
        (seed + " word").let { base -> (1..words).joinToString(" ") { base.split(" ")[it % 3] } }

    @Test
    fun `prose and ledger are split on the delimiter`() {
        val raw = """
            Mira crossed the bridge and found Deven waiting.
            ${BeatContract.LEDGER_DELIMITER}
            {"characters":[{"name":"Mira","note":"courier"}],"lastBeat":"On the bridge."}
        """.trimIndent()

        val response = BeatContract.split(raw)
        assertEquals("Mira crossed the bridge and found Deven waiting.", response.prose)
        assertNotNull(response.ledgerPatch)
        assertEquals("Mira", response.ledgerPatch!!.characters.single().name)
    }

    @Test
    fun `a missing ledger yields prose and a null patch rather than failing`() {
        val response = BeatContract.split("Mira crossed the bridge.")
        assertEquals("Mira crossed the bridge.", response.prose)
        assertNull(response.ledgerPatch)
    }

    @Test
    fun `an unparseable ledger never costs us the prose`() {
        val raw = "Mira crossed the bridge.\n${BeatContract.LEDGER_DELIMITER}\n{not json at all"
        val response = BeatContract.split(raw)
        assertEquals("Mira crossed the bridge.", response.prose)
        assertNull(response.ledgerPatch)
    }

    @Test
    fun `a fenced ledger is still recovered`() {
        val raw = "Prose here.\n${BeatContract.LEDGER_DELIMITER}\n```json\n{\"places\":[\"Harbour\"]}\n```"
        assertEquals(listOf("Harbour"), BeatContract.split(raw).ledgerPatch?.places)
    }

    @Test
    fun `scaffolding labels and code fences are stripped from prose`() {
        assertEquals("Mira crossed.", BeatContract.cleanProse("CONDENSED: Mira crossed."))
        assertEquals("Mira crossed.", BeatContract.cleanProse("```\nMira crossed.\n```"))
    }

    @Test
    fun `a faithful retelling is accepted`() {
        val good = "Mira crossed the harbour bridge before dawn. Deven waited at the far end " +
            "with the ledger under his coat and told her the shipment had moved, that Kestrel knew."
        assertEquals(BeatRejection.NONE, BeatContract.evaluate(good, source, budgetWords = 30))
    }

    @Test
    fun `meta framing is rejected`() {
        listOf(
            "In this chapter, Mira crosses a bridge and meets Deven.",
            "This passage describes Mira's journey across the harbour with Deven and Kestrel.",
            "To summarise: Mira met Deven and learned about Kestrel and the shipment.",
            "The author introduces Mira, Deven and Kestrel in a night-time harbour scene.",
        ).forEach { bad ->
            assertEquals(
                "should have been rejected: $bad",
                BeatRejection.META_FRAMING,
                BeatContract.evaluate(bad, source, budgetWords = 10),
            )
        }
    }

    @Test
    fun `the author appearing mid-passage is not meta framing`() {
        // A story can contain an actual author character; only task-narration at the top
        // of the passage counts.
        val fine = "Mira crossed the harbour bridge before dawn and found Deven waiting. " +
            "He had once been the author of every route they used, and Kestrel knew it too."
        assertEquals(BeatRejection.NONE, BeatContract.evaluate(fine, source, budgetWords = 10))
    }

    @Test
    fun `output far under budget is rejected as truncated`() {
        assertEquals(
            BeatRejection.TOO_SHORT,
            BeatContract.evaluate("Mira crossed.", source, budgetWords = 200),
        )
    }

    @Test
    fun `losing every proper noun is rejected`() {
        val generic = "She crossed a bridge before dawn and met a man who told her the " +
            "shipment had been moved and that someone dangerous already knew about it."
        assertEquals(BeatRejection.LOST_NAMES, BeatContract.evaluate(generic, source, budgetWords = 20))
    }

    @Test
    fun `empty output is rejected`() {
        assertEquals(BeatRejection.EMPTY, BeatContract.evaluate("   ", source, budgetWords = 100))
    }

    @Test
    fun `a source with no names is not judged on names`() {
        val plain = "the wind rose over the water and did not fall again for three days"
        val out = "the wind rose over the water and stayed for three days running"
        assertEquals(BeatRejection.NONE, BeatContract.evaluate(out, plain, budgetWords = 8))
    }
}
