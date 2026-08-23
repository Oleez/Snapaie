package com.snapaie.android.domain.condense

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryLedgerTest {

    @Test
    fun `a patch folds in newest first`() {
        val base = StoryLedger(
            characters = listOf(LedgerCharacter("Deven", "ledger keeper")),
            places = listOf("Harbour"),
            openThreads = listOf("Who moved the shipment?"),
            lastBeat = "On the bridge.",
            pov = "third person limited, past tense",
        )
        val merged = base.merge(
            StoryLedger(
                characters = listOf(LedgerCharacter("Kestrel", "unseen")),
                places = listOf("Customs house"),
                lastBeat = "At the customs house door.",
            ),
        )
        assertEquals(listOf("Kestrel", "Deven"), merged.characters.map { it.name })
        assertEquals(listOf("Customs house", "Harbour"), merged.places)
        assertEquals("At the customs house door.", merged.lastBeat)
        assertEquals("Who moved the shipment?", merged.openThreads.single())
    }

    @Test
    fun `voice is set once and does not drift`() {
        val base = StoryLedger(pov = "first person present")
        val merged = base.merge(StoryLedger(pov = "third person past"))
        assertEquals("first person present", merged.pov)
    }

    @Test
    fun `a blank update never erases what we already knew`() {
        val base = StoryLedger(lastBeat = "On the bridge.", timeline = "Before dawn.")
        val merged = base.merge(StoryLedger(places = listOf("Docks")))
        assertEquals("On the bridge.", merged.lastBeat)
        assertEquals("Before dawn.", merged.timeline)
    }

    @Test
    fun `a character keeps their note when a later patch mentions them without one`() {
        val base = StoryLedger(characters = listOf(LedgerCharacter("Mira", "courier")))
        val merged = base.merge(StoryLedger(characters = listOf(LedgerCharacter("Mira"))))
        assertEquals("courier", merged.characters.single().note)
    }

    @Test
    fun `names are deduplicated case insensitively`() {
        val merged = StoryLedger(characters = listOf(LedgerCharacter("mira")))
            .merge(StoryLedger(characters = listOf(LedgerCharacter("Mira", "courier"))))
        assertEquals(1, merged.characters.size)
        assertEquals("Mira", merged.characters.single().name)
    }

    @Test
    fun `caps evict the least recently mentioned`() {
        var ledger = StoryLedger()
        repeat(30) { index ->
            ledger = ledger.merge(StoryLedger(characters = listOf(LedgerCharacter("Person$index"))))
        }
        assertEquals(StoryLedger.MAX_CHARACTERS, ledger.characters.size)
        // The most recent survives; the first one is long gone.
        assertEquals("Person29", ledger.characters.first().name)
        assertFalse(ledger.characters.any { it.name == "Person0" })
    }

    @Test
    fun `render is compact and omits empty sections`() {
        val rendered = StoryLedger(
            characters = listOf(LedgerCharacter("Mira", "courier")),
            lastBeat = "On the bridge.",
        ).render()
        assertTrue(rendered.contains("WHO: Mira (courier)"))
        assertTrue(rendered.contains("PREVIOUSLY: On the bridge."))
        assertFalse(rendered.contains("WHERE:"))
        assertFalse(rendered.contains("UNRESOLVED:"))
    }

    @Test
    fun `parse recovers a ledger from fenced or chatty output`() {
        assertEquals(
            "Mira",
            StoryLedger.parse("""```json
            {"characters":[{"name":"Mira"}]}
            ```""")?.characters?.single()?.name,
        )
        assertEquals(
            listOf("Harbour"),
            StoryLedger.parse("""Sure! Here is the state: {"places":["Harbour"]} Hope that helps.""")?.places,
        )
    }

    @Test
    fun `parse returns null for junk so the caller can carry the old ledger forward`() {
        assertNull(StoryLedger.parse(""))
        assertNull(StoryLedger.parse("not json"))
        assertNull(StoryLedger.parse("{}"))
    }

    @Test
    fun `encode and decode round trip`() {
        val ledger = StoryLedger(
            characters = listOf(LedgerCharacter("Mira", "courier")),
            openThreads = listOf("Who is Kestrel?"),
            pov = "third person limited",
        )
        assertEquals(ledger, StoryLedger.decode(StoryLedger.encode(ledger)))
        assertEquals(StoryLedger.EMPTY, StoryLedger.decode("corrupted"))
    }

    @Test
    fun `long fields are truncated so the ledger cannot crowd out the passage`() {
        val merged = StoryLedger().merge(StoryLedger(lastBeat = "x".repeat(1_000)))
        assertEquals(StoryLedger.MAX_FIELD_CHARS, merged.lastBeat.length)
    }
}
