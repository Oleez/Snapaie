package com.snapaie.android.domain.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LengthOverrideDetectorTest {

    @Test
    fun `detects explicit word caps`() {
        assertEquals("<=50 words", LengthOverrideDetector.detect("answer in no more than 50 words")?.target)
        assertEquals("<=25 words", LengthOverrideDetector.detect("give me 25 words on this")?.target)
    }

    @Test
    fun `detects sentence and line requests`() {
        assertEquals("1 sentence (max 25 words)", LengthOverrideDetector.detect("explain in one sentence")?.target)
        assertEquals("2 sentences (max 45 words)", LengthOverrideDetector.detect("two sentences please")?.target)
        assertEquals("1 line (max 20 words)", LengthOverrideDetector.detect("just one line")?.target)
    }

    @Test
    fun `detects tldr and brevity phrasing`() {
        assertNotNull(LengthOverrideDetector.detect("tldr"))
        assertNotNull(LengthOverrideDetector.detect("keep it short"))
        assertNotNull(LengthOverrideDetector.detect("summarize this"))
    }

    @Test
    fun `plain questions have no override`() {
        assertNull(LengthOverrideDetector.detect("what does this passage actually mean?"))
    }
}

class ChatPromptBuilderTest {

    private fun build(input: ChatPromptInput) = ChatPromptBuilder.build(input)

    @Test
    fun `sections appear in the ported order`() {
        val prompt = build(
            ChatPromptInput(
                message = "What should I do next?",
                originalText = "Some selected page text.",
                originalExplanation = "A prior explanation.",
                persona = Persona.Professional,
            ),
        )
        val order = listOf(
            "OUTPUT LANGUAGE",
            "BEST ANSWER LAYER",
            "REGIONAL TERM DISAMBIGUATION",
            "IDENTITY RULES",
            "USER GENDER",
            "ANSWER PRIORITY",
            "RESPONSE RULES",
            "ORIGINAL SELECTED TEXT",
            "ORIGINAL EXPLANATION",
            "ASSISTANT RESPONSE:",
        )
        var cursor = -1
        order.forEach { marker ->
            val index = prompt.indexOf(marker)
            assertTrue("Missing section: $marker", index >= 0)
            assertTrue("Out of order: $marker", index > cursor)
            cursor = index
        }
    }

    @Test
    fun `length override block is injected before the persona`() {
        val prompt = build(ChatPromptInput(message = "tldr this", persona = Persona.Funny))
        val overrideIndex = prompt.indexOf("USER LENGTH OVERRIDE")
        assertTrue(overrideIndex >= 0)
        assertTrue(overrideIndex < prompt.indexOf("Funny & Witty"))
    }

    @Test
    fun `auto persona includes the lens briefing`() {
        val prompt = build(ChatPromptInput(message = "hello", persona = Persona.Auto))
        assertTrue(prompt.contains("AUTO LENS BRIEFING"))
    }

    @Test
    fun `non-auto persona omits the lens briefing`() {
        val prompt = build(ChatPromptInput(message = "hello", persona = Persona.Stoic))
        assertFalse(prompt.contains("AUTO LENS BRIEFING"))
    }

    @Test
    fun `non-english selection forces the output language`() {
        val prompt = build(ChatPromptInput(message = "hola", languageCode = "es"))
        assertTrue(prompt.contains("Spanish"))
        assertTrue(prompt.contains("Assistant reply language: Spanish (es) only"))
    }

    @Test
    fun `custom instructions only appear when provided`() {
        val without = build(ChatPromptInput(message = "hi"))
        assertFalse(without.contains("CUSTOM USER INSTRUCTIONS"))
        val with = build(ChatPromptInput(message = "hi", customInstructions = "Always answer as a pirate."))
        assertTrue(with.contains("CUSTOM USER INSTRUCTIONS"))
        assertTrue(with.contains("pirate"))
    }

    @Test
    fun `conversation history is capped to the recent window`() {
        val turns = (1..40).map { ChatTurn(if (it % 2 == 0) "ai" else "user", "message number $it") }
        val prompt = build(ChatPromptInput(message = "next", recentTurns = turns))
        assertFalse(prompt.contains("message number 1 "))
        assertTrue(prompt.contains("message number 40"))
    }

    @Test
    fun `gender setting drives the pronoun guidance`() {
        assertTrue(build(ChatPromptInput(message = "hi", userGender = "female")).contains("she/her"))
        assertTrue(build(ChatPromptInput(message = "hi", userGender = "other")).contains("they/them"))
        assertTrue(build(ChatPromptInput(message = "hi")).contains("not specified"))
    }
}

class WorkBoxParserTest {

    @Test
    fun `splits deliverable boxes from surrounding prose`() {
        val content = """
            Here's a draft you can send.
            [[WORK: Email to landlord]]
            Dear Sam, the radiator is still broken.
            [[/WORK]]
            Want it more formal?
        """.trimIndent()
        val segments = WorkBoxParser.parse(content)
        assertEquals(3, segments.size)
        assertFalse(segments[0].isWork)
        assertTrue(segments[1].isWork)
        assertEquals("Email to landlord", segments[1].title)
        assertTrue(segments[1].text.startsWith("Dear Sam"))
        assertFalse(segments[2].isWork)
    }

    @Test
    fun `plain replies stay as a single segment`() {
        val segments = WorkBoxParser.parse("Just a normal answer.")
        assertEquals(1, segments.size)
        assertFalse(segments[0].isWork)
    }
}
