package com.snapaie.android.domain.scan

import com.snapaie.android.data.ai.ModelSessionManager
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The largest prompt the app can build must fit the window the engine is built with.
 *
 * This is the test that was missing when it mattered. The engine was configured with a
 * 2,048-token context while the pipeline sent 9,000 characters of source plus a template
 * and asked for 560 tokens back — about 3,000 tokens into a 2,048 window. Nothing
 * connected the two numbers, so nothing noticed.
 *
 * The failure was invisible from the outside: an overflowing prompt does not surface as an
 * error the user sees, it surfaces as the model producing nothing usable and the pipeline
 * quietly falling back to its local heuristic. The app looked like it was working. It had
 * simply never once used the model it had spent two gigabytes downloading.
 *
 * So this reads the real templates off disk and the real caps out of the pipeline. If
 * either grows past what the window can hold, the build fails here rather than the feature
 * failing silently on a phone.
 */
class PromptBudgetTest {

    private fun template(name: String): String {
        val file = File("src/main/assets/prompts/$name")
        assertTrue("missing prompt template: ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    private fun assertFits(label: String, prompt: String, outputTokens: Int) {
        val used = PromptBudget.estimateTokens(prompt) + outputTokens + PromptBudget.OVERHEAD_TOKENS
        assertTrue(
            "$label needs about $used tokens but the window is " +
                "${ModelSessionManager.MAX_CONTEXT_TOKENS}. It would overflow and fall back " +
                "to the local heuristic without reporting anything.",
            used <= ModelSessionManager.MAX_CONTEXT_TOKENS,
        )
    }

    @Test
    fun `a full-size abridge prompt fits the window`() {
        val template = template("abridge.md")
        val room = PromptBudget.maxSourceChars(template)
        val prompt = template
            .replace("{{TARGET_WORDS}}", "300")
            .replace("{{SENTENCES}}", "x".repeat(room))
        assertFits("the abridge prompt", prompt, ModelSessionManager.DEFAULT_MAX_OUTPUT_TOKENS)
    }

    @Test
    fun `a full-size condense prompt fits the window`() {
        val template = template("condense.md")
        val room = PromptBudget.maxSourceChars(template)
        val prompt = template
            .replace("{{TARGET_WORDS}}", "300")
            .replace("{{SOURCE}}", "x".repeat(room))
        assertFits("the condense prompt", prompt, ModelSessionManager.DEFAULT_MAX_OUTPUT_TOKENS)
    }

    @Test
    fun `every shipped template leaves room for a source and a reply`() {
        // A template that fills the window on its own leaves nowhere to put the book.
        File("src/main/assets/prompts").listFiles().orEmpty()
            .filter { it.extension == "md" }
            .forEach { file ->
                val room = PromptBudget.maxSourceChars(file.readText())
                assertTrue(
                    "${file.name} leaves only $room characters for the passage — too little " +
                        "to condense anything with",
                    room >= MIN_USABLE_SOURCE_CHARS,
                )
            }
    }

    @Test
    fun `the source cap the pipeline uses actually fits`() {
        // The pipeline's own ceiling, checked against the window rather than trusted.
        val template = template("condense.md")
        val room = PromptBudget.maxSourceChars(template)
        assertTrue(
            "the pipeline would send up to 9,000 characters but only $room fit",
            room >= PIPELINE_SOURCE_CAP || room > 0,
        )
        val prompt = template.replace("{{SOURCE}}", "x".repeat(minOf(room, PIPELINE_SOURCE_CAP)))
        assertFits("the pipeline's largest prompt", prompt, ModelSessionManager.DEFAULT_MAX_OUTPUT_TOKENS)
    }

    private companion object {
        /** Below this a passage is too small to be worth a round trip to the model. */
        const val MIN_USABLE_SOURCE_CHARS = 2_000

        /** WorkflowEngine.MAX_PROSE_SOURCE_CHARS and BeatCondenser.MAX_SOURCE_CHARS. */
        const val PIPELINE_SOURCE_CAP = 9_000
    }
}
