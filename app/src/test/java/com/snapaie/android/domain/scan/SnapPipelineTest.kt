package com.snapaie.android.domain.scan

import com.snapaie.android.data.ai.TextGenerator
import com.snapaie.android.data.model.BookScanDraft
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's core function: a snap produces a shorter version of the page.
 *
 * This had no test, which is why it kept breaking in ways nothing caught — a missing image
 * encoder, an error string pasted into the reply, a silent empty return. Each case below is
 * a failure that actually shipped.
 *
 * The guarantee being pinned: **a snap always produces prose, whatever goes wrong.**
 */
class SnapPipelineTest {

    private val page = """
        Everyone holds his fortune in his own hands, like a sculptor the raw material he
        will fashion into a figure. In this activity of working the raw material into the
        form we wish, the practised skill must be learned and attentively cultivated.

        There exists a form of power and intelligence that represents the high point of
        human potential. It is the source of the greatest achievements and discoveries in
        history. It is an intelligence that is not taught in our schools.

        It often comes in a period of tension: facing a deadline, the urgent need to solve
        a problem, a crisis of sorts. Pressed by circumstance, we feel unusually energised
        and focused, and our minds settle on the task with a clarity we rarely command.
    """.trimIndent()

    /** A real file, because the pipeline will not send a path that is not on disk. */
    private val photo: java.io.File by lazy {
        java.io.File.createTempFile("page", ".jpg").apply { writeBytes(ByteArray(64)); deleteOnExit() }
    }

    private fun draft(text: String = page, image: String = "") =
        BookScanDraft(pageText = text, imagePath = image)

    private class Fake(
        val installed: Boolean = true,
        override val visionAllowed: Boolean = true,
        val onText: (String) -> Flow<String> = { flow { emit(GOOD_PROSE) } },
        val onImage: (String) -> Flow<String> = { flow { emit(GOOD_PROSE) } },
    ) : TextGenerator {
        var textCalls = 0
        var imageCalls = 0
        override fun isModelInstalled() = installed
        override fun stream(prompt: String, maxOutputTokens: Int): Flow<String> {
            textCalls++
            return onText(prompt)
        }
        override fun streamWithImage(prompt: String, imagePath: String, maxOutputTokens: Int): Flow<String> {
            imageCalls++
            return onImage(prompt)
        }
    }

    private fun engineWith(generator: TextGenerator) = WorkflowEngine(
        sessionManager = generator,
        prompts = { path ->
            if (path.endsWith("condense_page.md")) "Retell it in {{TARGET_WORDS}} words. {{SOURCE_BLOCK}}" else ""
        },
        scanPrompts = object : ScanPrompts {
            override fun buildScanPrompt(draft: BookScanDraft) = "scan"
            override fun buildRepairPrompt(draft: BookScanDraft, previousOutput: String) = "repair"
        },
    )

    private suspend fun proseFrom(generator: TextGenerator, draft: BookScanDraft): String {
        var prose = ""
        engineWith(generator).run(draft).collect { event ->
            if (event is WorkflowEvent.Result) prose = event.result.condensedProse
        }
        return prose
    }

    @Test
    fun `a good reply becomes the shorter version`() = runTest {
        val prose = proseFrom(Fake(), draft())
        assertTrue("no prose produced", prose.isNotBlank())
        assertTrue(prose.contains("sculptor"))
    }

    @Test
    fun `with no model installed it still shortens the page locally`() = runTest {
        val prose = proseFrom(Fake(installed = false), draft())
        assertTrue("nothing produced without a model", prose.isNotBlank())
        assertTrue("not shorter than the page", prose.length < page.length)
    }

    @Test
    fun `a missing vision executor falls through to the text path`() = runTest {
        // The exact failure seen on device: the engine had no image encoder loaded.
        val fake = Fake(
            onImage = {
                flow<String> {
                    error("Status Code: 3. Message: Vision executor should not be null, please TryLoadingVisionExecutor() first.")
                }
            },
        )
        val prose = proseFrom(fake, draft(image = photo.absolutePath))
        assertTrue("image path was not tried", fake.imageCalls > 0)
        assertTrue("did not fall through to text", fake.textCalls > 0)
        assertTrue("no prose produced", prose.isNotBlank())
    }

    @Test
    fun `a runtime error is never printed into the page`() = runTest {
        // It used to be sent down the same channel as the text and pasted onto the front.
        val fake = Fake(
            onImage = { flow { emit("LiteRT-LM stream error: Status Code: 3. Message: Vision executor should not be null.") } },
        )
        val prose = proseFrom(fake, draft(image = photo.absolutePath))
        listOf("LiteRT", "Status Code", "executor", "stream error").forEach {
            assertFalse("leaked '$it' into the reply:\n$prose", prose.contains(it, ignoreCase = true))
        }
    }

    @Test
    fun `an empty model reply still yields a shorter version`() = runTest {
        val prose = proseFrom(Fake(onText = { flow { emit("") } }, onImage = { flow { emit("") } }), draft())
        assertTrue("silence produced nothing at all", prose.isNotBlank())
    }

    @Test
    fun `a model that throws on everything still yields a shorter version`() = runTest {
        val boom: (String) -> Flow<String> = { flow { error("engine gone") } }
        val prose = proseFrom(Fake(onText = boom, onImage = boom), draft())
        assertTrue("a dead engine produced nothing", prose.isNotBlank())
    }

    @Test
    fun `a summary-shaped reply does not cost the whole result`() = runTest {
        val summary = "In this chapter, the author describes how people shape their fortunes " +
            "and discusses a rare form of intelligence that schools do not teach anyone."
        val prose = proseFrom(Fake(onText = { flow { emit(summary) } }, onImage = { flow { emit(summary) } }), draft())
        assertTrue("rejection wiped the result", prose.isNotBlank())
    }

    @Test
    fun `vision is skipped entirely once disallowed`() = runTest {
        val fake = Fake(visionAllowed = false)
        proseFrom(fake, draft(image = photo.absolutePath))
        assertTrue("image was sent despite vision being off", fake.imageCalls == 0)
        assertTrue("text path was not used", fake.textCalls > 0)
    }

    @Test
    fun `the result is always shorter than the page`() = runTest {
        listOf(
            Fake(),
            Fake(installed = false),
            Fake(onText = { flow { emit("") } }, onImage = { flow { emit("") } }),
        ).forEach { fake ->
            val prose = proseFrom(fake, draft())
            assertTrue("empty for $fake", prose.isNotBlank())
            assertTrue("not shorter for $fake", prose.length < page.length)
        }
    }

    private companion object {
        val GOOD_PROSE = "Everyone holds his fortune in his own hands, like a sculptor with raw " +
            "material, and the skill of shaping it must be learned. There is a form of " +
            "intelligence behind the greatest discoveries that no school teaches. It arrives " +
            "under pressure: a deadline, a crisis, a problem that will not wait."
    }
}
