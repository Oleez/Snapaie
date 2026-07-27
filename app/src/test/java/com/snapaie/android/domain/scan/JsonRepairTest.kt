package com.snapaie.android.domain.scan

import com.snapaie.android.data.model.BookScanDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonRepairTest {

    private val parser = StructuredOutputParser()

    @Test
    fun `parses clean json`() {
        val raw = """{"conciseMeaning":"Short and sharp.","coreIdea":"The point."}"""
        val outcome = parser.parse(raw)
        assertTrue(outcome is ParseOutcome.Structured)
        assertEquals("Short and sharp.", (outcome as ParseOutcome.Structured).result.conciseMeaning)
    }

    @Test
    fun `strips markdown fences`() {
        val raw = """
            ```json
            {"conciseMeaning":"Fenced output.","coreIdea":"Still valid."}
            ```
        """.trimIndent()
        val outcome = parser.parse(raw)
        assertTrue(outcome is ParseOutcome.Structured)
        assertEquals("Fenced output.", (outcome as ParseOutcome.Structured).result.conciseMeaning)
    }

    @Test
    fun `recovers json wrapped in model chatter`() {
        val raw = """
            Sure! Here is the compressed result you asked for:
            {"conciseMeaning":"Buried but findable.","coreIdea":"Extract me."}
            Let me know if you want another pass.
        """.trimIndent()
        val outcome = parser.parse(raw)
        assertTrue(outcome is ParseOutcome.Structured)
        assertEquals("Buried but findable.", (outcome as ParseOutcome.Structured).result.conciseMeaning)
    }

    @Test
    fun `handles nested braces and escaped quotes`() {
        val raw = """{"conciseMeaning":"He said \"hi\" then left.","importantVocabulary":[{"word":"a","meaning":"b","simplerVersion":"c"}]}"""
        val outcome = parser.parse(raw)
        assertTrue(outcome is ParseOutcome.Structured)
        val result = (outcome as ParseOutcome.Structured).result
        assertEquals(1, result.importantVocabulary.size)
    }

    @Test
    fun `truncated json is unparseable`() {
        val raw = """{"conciseMeaning":"Cut off mid"""
        assertTrue(parser.parse(raw) is ParseOutcome.Unparseable)
    }

    @Test
    fun `empty output is unparseable`() {
        assertTrue(parser.parse("") is ParseOutcome.Unparseable)
        assertTrue(parser.parse(null) is ParseOutcome.Unparseable)
    }

    @Test
    fun `json with no useful fields is rejected`() {
        assertTrue(parser.parse("""{"compressionScore":42}""") is ParseOutcome.Unparseable)
    }

    @Test
    fun `bare array is wrapped under the requested key`() {
        val candidates = JsonRepair.candidates("""[{"statement":"x","isTrue":true}]""", arrayWrapKey = "questions")
        assertTrue(candidates.any { it.startsWith("""{"questions":[""") })
    }

    @Test
    fun `balanced object ignores braces inside strings`() {
        val extracted = JsonRepair.balancedObject("""{"a":"} not the end","b":1}""")
        assertEquals("""{"a":"} not the end","b":1}""", extracted)
    }

    @Test
    fun `bare array finder skips arrays nested inside objects`() {
        assertNull(JsonRepair.bareArray("""{"questions":[1,2]}"""))
        assertNotNull(JsonRepair.bareArray("""[1,2]"""))
    }

    @Test
    fun `long prose falls back to plain text render instead of an error`() {
        val prose = "The author argues that habits compound quietly. ".repeat(4)
        val result = parser.plainTextOrHeuristic(BookScanDraft(pageText = "source"), prose, null)
        assertTrue(result.plainTextFallback.isNotBlank())
        assertTrue(result.isPlainTextOnly)
    }

    @Test
    fun `stream error falls back to heuristic draft with an explanation`() {
        val draft = BookScanDraft(pageText = "A long enough sentence to seed the heuristic draft generator.")
        val result = parser.plainTextOrHeuristic(draft, "LiteRT-LM stream error: boom", null)
        assertTrue(result.conciseMeaning.contains("local model reported an error", ignoreCase = true))
    }
}
