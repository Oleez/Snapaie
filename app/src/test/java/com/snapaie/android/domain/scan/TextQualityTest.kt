package com.snapaie.android.domain.scan

import org.junit.Assert.assertEquals
import org.junit.Test

class TextQualityTest {

    private val realPage = """
        Mira crossed the harbour bridge before dawn, counting the lanterns as she went.
        Deven was waiting at the far end with the ledger tucked under his coat, and he did
        not look at her when she reached him. They moved the shipment, he said quietly.
    """.trimIndent()

    @Test
    fun `clean prose passes`() {
        assertEquals(TextQuality.Verdict.GOOD, TextQuality.assess(realPage))
    }

    @Test
    fun `an empty or near-empty page is unusable`() {
        listOf("", "   ", "a", "Page 12") .forEach {
            assertEquals("should be unusable: '$it'", TextQuality.Verdict.UNUSABLE, TextQuality.assess(it))
        }
    }

    @Test
    fun `symbol soup is unusable`() {
        // What a recogniser returns when it gives up on the script entirely.
        val noise = "|| ~~ >< ][ %% ## @@ ^^ ** (( )) ++ == << >> ?? !! ,, .. ;; ::"
        assertEquals(TextQuality.Verdict.UNUSABLE, TextQuality.assess(noise))
    }

    @Test
    fun `a page shattered into fragments is unusable`() {
        // The classic bad-threshold symptom: thin strokes eaten, words broken up.
        val shattered = "Th e ha rb ou r br id ge be fo re da wn wi th th e le dg er un de r hi s co at"
        assertEquals(TextQuality.Verdict.UNUSABLE, TextQuality.assess(shattered))
    }

    @Test
    fun `a short but clean snippet is weak rather than unusable`() {
        val snippet = "Mira crossed the harbour bridge before dawn and counted every lantern."
        assertEquals(TextQuality.Verdict.WEAK, TextQuality.assess(snippet))
    }

    @Test
    fun `prose with heavy punctuation is still good`() {
        val punctuated = """
            "They moved it," he said. "All of it — last night, before the tide turned."
            She stopped. "And Kestrel?" "Kestrel knows; that's rather the point, isn't it?"
            He shrugged, which told her more than any answer would have done that evening.
        """.trimIndent()
        assertEquals(TextQuality.Verdict.GOOD, TextQuality.assess(punctuated))
    }

    @Test
    fun `accented text is not mistaken for noise`() {
        val french = """
            Mira traversa le pont du port avant l'aube, comptant les lanternes une à une.
            Deven l'attendait à l'autre bout, le registre glissé sous son manteau très usé.
            Ils ont déplacé la cargaison, dit-il enfin, sans jamais lever les yeux vers elle.
        """.trimIndent()
        assertEquals(TextQuality.Verdict.GOOD, TextQuality.assess(french))
    }
}
