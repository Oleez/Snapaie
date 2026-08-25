package com.snapaie.android.domain.condense

import com.snapaie.android.data.ai.TextGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole-book path, which is the one the product is named for.
 *
 * It had no test at all: exercising it meant a real engine, a real Context and two
 * gigabytes of weights, so every change to it was guesswork. These pin the properties a
 * reader would notice if they broke — that the result is the author's own prose, in the
 * author's order, and that a bad reply degrades instead of failing the beat.
 */
class BeatCondenserTest {

    private class Fake(
        private val reply: (String) -> String,
        private val installed: Boolean = true,
    ) : TextGenerator {
        val prompts = mutableListOf<String>()
        override fun isModelInstalled() = installed
        override val visionAllowed = true
        override fun stream(prompt: String, maxOutputTokens: Int): Flow<String> {
            prompts += prompt
            return flow { emit(reply(prompt)) }
        }
        override fun streamWithImage(prompt: String, imagePath: String, maxOutputTokens: Int) =
            flow { emit(reply(prompt)) }
    }

    /** Mirrors the real templates' placeholders so an unfilled slot fails here too. */
    private val prompts = com.snapaie.android.domain.scan.PromptSource { path ->
        when {
            path.endsWith("abridge.md") -> "Keep {{TARGET_WORDS}} words.\n{{SENTENCES}}"
            path.endsWith("condense.md") ->
                "Retell in {{TARGET_WORDS}} words.\n{{LEDGER}}\n{{PREVIOUS_TAIL}}\n{{SOURCE}}"
            else -> ""
        }
    }

    private val source = buildString {
        append("Gregor Samsa woke from uneasy dreams. ")
        append("He found himself changed into a monstrous vermin. ")
        append("His room was a proper room, only rather too small. ")
        append("Above the table hung the picture he had cut from a magazine. ")
        append("It showed a lady in a fur hat and a fur boa. ")
        append("The rain struck the window pane. ")
        append("He thought about his work, which he detested. ")
        append("The chief was a difficult man who sat high above his clerks. ")
    }

    private fun condenser(fake: Fake) = BeatCondenser(fake, prompts)

    @Test
    fun `an abridged beat is made of the author's own sentences`() = runTest {
        val fake = Fake({ "0, 1, 3, 6" })
        val beat = condenser(fake).condense(source, StoryLedger(), "", budgetWords = 30)

        assertTrue("did not abridge", beat.wasAbridged)
        assertFalse("should not have fallen back", beat.usedFallback)
        Abridger.split(beat.prose).forEach {
            assertTrue("'${it.text}' was not in the source verbatim", source.contains(it.text))
        }
    }

    @Test
    fun `an abridged beat keeps the book's order`() = runTest {
        // The model is free to answer out of order; the edition must not be.
        val fake = Fake({ "6, 0, 3" })
        val beat = condenser(fake).condense(source, StoryLedger(), "", budgetWords = 25)

        val kept = Abridger.split(beat.prose).map { source.indexOf(it.text) }
        assertEquals("order was not the book's", kept.sorted(), kept)
    }

    @Test
    fun `a reply that cuts too deep falls through to the retelling`() = runTest {
        // One sentence out of eight against a 60-word budget is a gutted beat, not an
        // abridgement — the ladder should get a turn rather than shipping it.
        val retold = "Gregor woke transformed, and thought bitterly of the work he detested."
        val fake = Fake({ prompt -> if (prompt.startsWith("Keep")) "0" else retold })
        val beat = condenser(fake).condense(source, StoryLedger(), "", budgetWords = 60)

        assertFalse("should not have accepted a gutted abridgement", beat.wasAbridged)
        assertTrue("the retelling never ran", fake.prompts.any { it.startsWith("Retell") })
    }

    @Test
    fun `an unusable reply still produces a beat`() = runTest {
        // Nothing may fail a beat: a hole in a story is not recoverable.
        val fake = Fake({ "" })
        val beat = condenser(fake).condense(source, StoryLedger(), "", budgetWords = 40)

        assertTrue("a beat must always produce text", beat.prose.isNotBlank())
        assertTrue("should be marked as fallback", beat.usedFallback)
    }

    @Test
    fun `the abridge prompt carries the numbered sentences and the target`() = runTest {
        val fake = Fake({ "0, 1" })
        condenser(fake).condense(source, StoryLedger(), "", budgetWords = 30)

        val prompt = fake.prompts.first()
        assertTrue("target words never filled in", prompt.contains("30"))
        assertTrue("sentences never numbered", prompt.contains("0. Gregor Samsa"))
        assertFalse("placeholder left unfilled", prompt.contains("{{"))
    }
}
