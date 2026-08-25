package com.snapaie.android.domain.scan

import com.snapaie.android.data.ai.TextGenerator
import com.snapaie.android.data.model.BookScanDraft
import com.snapaie.android.data.model.ExplainStyle
import com.snapaie.android.domain.condense.Abridger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

    private fun draft(
        text: String = page,
        image: String = "",
        style: ExplainStyle = ExplainStyle.Auto,
    ) = BookScanDraft(pageText = text, imagePath = image, mode = style)

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

    private fun engineWith(
        generator: TextGenerator,
        nowNanos: () -> Long = System::nanoTime,
    ) = WorkflowEngine(
        sessionManager = generator,
        prompts = { path ->
            // Mirrors the real template's placeholders, so a slot that stops being filled
            // in production fails here too.
            if (path.endsWith("abridge.md")) {
                "Keep about {{TARGET_WORDS}} words.\n{{SENTENCES}}"
            } else if (path.endsWith("condense_page.md")) {
                "Retell this page shorter.\n{{STYLE}}\nAim for about {{TARGET_WORDS}} words.\n{{SOURCE_BLOCK}}"
            } else {
                ""
            }
        },
        scanPrompts = object : ScanPrompts {
            override fun buildScanPrompt(draft: BookScanDraft) = "scan"
            override fun buildRepairPrompt(draft: BookScanDraft, previousOutput: String) = "repair"
        },
        nowNanos = nowNanos,
    )

    private suspend fun proseFrom(
        generator: TextGenerator,
        draft: BookScanDraft,
        nowNanos: () -> Long = System::nanoTime,
    ): String {
        var prose = ""
        engineWith(generator, nowNanos).run(draft).collect { event ->
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
        // The device from the report: its build has no image encoder, so every photo used
        // to end in that error. It no longer even asks — the recognised text is enough —
        // and the page comes back from the author's own sentences instead.
        val prose = proseFrom(fake, draft(image = photo.absolutePath))
        assertEquals("a device that cannot read images should not be asked to", 0, fake.imageCalls)
        assertTrue("no prose produced", prose.isNotBlank())
    }

    @Test
    fun `an unreadable photo with no text is explained, not left blank`() = runTest {
        // Nothing recognised and nothing readable: there is genuinely no page to shorten,
        // and saying so is better than inventing one or showing an empty panel.
        val fake = Fake(onImage = { flow<String> { error("Vision executor should not be null") } })
        val prose = proseFrom(fake, draft(text = "", image = photo.absolutePath))
        assertTrue("the photo was never tried", fake.imageCalls > 0)
        assertTrue("content was invented from an unreadable page", prose.isBlank())
    }

    @Test
    fun `a page the recogniser read does not wait for the photo to be read again`() = runTest {
        // Reading an image runs the encoder before a single word is generated, and on a
        // phone that is most of the wait. When the text is already in hand it buys nothing
        // — and paying for it on every snap is why a capture appeared to never finish.
        val fake = Fake()
        proseFrom(fake, draft(image = photo.absolutePath))
        assertEquals("the photo was read even though the page was already recognised", 0, fake.imageCalls)
    }

    @Test
    fun `a photo is still read when the recogniser found nothing`() = runTest {
        val fake = Fake()
        proseFrom(fake, draft(text = "", image = photo.absolutePath))
        assertTrue("the photo was never read", fake.imageCalls > 0)
    }

    @Test
    fun `a runtime error is never printed into the page`() = runTest {
        // It used to be sent down the same channel as the text and pasted onto the front.
        val fake = Fake(
            onImage = { flow { emit("LiteRT-LM stream error: Status Code: 3. Message: Vision executor should not be null.") } },
        )
        val prose = proseFrom(fake, draft(text = "", image = photo.absolutePath))
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
        /**
         * A snap that has run out of time should stop asking, not keep queueing calls.
         * Generous, because the point is that the number is bounded at all.
         */
        const val MAX_CALLS_BEFORE_GIVING_UP = 4

        val GOOD_PROSE = "Everyone holds his fortune in his own hands, like a sculptor with raw " +
            "material, and the skill of shaping it must be learned. There is a form of " +
            "intelligence behind the greatest discoveries that no school teaches. It arrives " +
            "under pressure: a deadline, a crisis, a problem that will not wait."
    }

    @Test
    fun `installing a model never leaves a page worse off than having none`() = runTest {
        // The bug a user found by watching it happen: with nothing installed the page was
        // shortened locally and always came back, and after downloading two gigabytes the
        // panel went empty. Whatever the model does, having one cannot be worse than not.
        val withoutModel = proseFrom(Fake(installed = false), draft())
        assertTrue("the baseline itself produced nothing", withoutModel.isNotBlank())

        val brokenModels = mapOf(
            "silence" to Fake(onText = { flow { emit("") } }, onImage = { flow { emit("") } }),
            "a dead engine" to Fake(
                onText = { flow { error("engine gone") } },
                onImage = { flow { error("engine gone") } },
            ),
            "runtime noise" to Fake(
                onText = { flow { emit("LiteRT-LM stream error: Status Code: 3.") } },
                onImage = { flow { emit("LiteRT-LM stream error: Status Code: 3.") } },
            ),
        )
        brokenModels.forEach { (label, fake) ->
            val prose = proseFrom(fake, draft())
            assertTrue("with a model installed, $label produced an empty page", prose.isNotBlank())
        }
    }

    @Test
    fun `a slow model cannot make a snap run without end`() = runTest {
        // Every call used to have its own four-minute ceiling with no budget for the snap
        // as a whole, so a photo could take a vision pass, then an abridgement of however
        // many runs the page needed, then a retelling — each free to run for minutes. The
        // ceilings added up, and a capture appeared to never finish.
        // Long enough to need a dozen runs, so the only thing that can keep the number of
        // calls small is the budget running out.
        val long = (0 until 3_000).joinToString(" ") { "Point number $it is worth recording here." }
        var calls = 0
        // A clock the model drives: every call it takes moves the snap a minute closer to
        // its budget, so the deadline is genuinely reached rather than merely configured.
        var clock = 0L
        val fake = Fake(onText = {
            calls++
            clock += 60_000L * 1_000_000L
            flow { emit(GOOD_PROSE) }
        })

        val prose = proseFrom(fake, draft(text = long), nowNanos = { clock })

        assertTrue("a snap must still produce a page", prose.isNotBlank())
        assertTrue(
            "the snap kept calling a model that never answers ($calls calls)",
            calls <= MAX_CALLS_BEFORE_GIVING_UP,
        )
        // What comes back is the author's own text, chosen locally once time ran out.
        Abridger.split(prose).forEach {
            assertTrue("'${it.text}' is not in the source verbatim", long.contains(it.text))
        }
    }

    @Test
    fun `a long capture reaches a composing style from end to end`() = runTest {
        // Bullets cannot be produced by deletion, so the page has to be written by the
        // model — and the model reads a window at a time. Truncating to that window would
        // bullet-point the opening of a long capture and silently drop the rest, with
        // nothing on screen to say so. The text is abridged to fit first instead.
        val long = (0 until 400).joinToString(" ") { "Point number $it is worth recording here." }
        var lastPrompt = ""
        val fake = Fake(onText = { prompt -> lastPrompt = prompt; flow { emit("- one and - two") } })
        proseFrom(fake, draft(text = long, style = ExplainStyle.Bullets))

        assertTrue("the composing prompt never ran", lastPrompt.isNotBlank())
        assertTrue(
            "the end of the capture never reached the model",
            lastPrompt.contains("Point number 399"),
        )
    }

    @Test
    fun `a composing style sends its instruction to the model`() = runTest {
        // Bullets, Steps and Analogy are different pieces of writing, not the same text
        // shortened, so they are composed rather than trimmed and carry an instruction.
        ExplainStyle.entries.filterNot { it.canAbridge }.forEach { style ->
            var seen = ""
            val fake = Fake(onText = { prompt -> seen = prompt; flow { emit(GOOD_PROSE) } })
            proseFrom(fake, draft(style = style))
            assertTrue(
                "${style.label} never reached the prompt",
                seen.contains(style.condenseInstruction),
            )
        }
    }

    @Test
    fun `an abridging style keeps the author's own sentences`() = runTest {
        // The property that makes an abridgement read like the book: every sentence that
        // survives appears in the source exactly as written.
        val fake = Fake(onText = { flow { emit("0, 2") } })
        val prose = proseFrom(fake, draft(style = ExplainStyle.Concise))
        assertTrue("nothing produced", prose.isNotBlank())
        Abridger.split(prose).forEach { sentence ->
            assertTrue("'${sentence.text}' is not in the source verbatim", page.contains(sentence.text))
        }
    }

    @Test
    fun `abridging never invents or reorders`() = runTest {
        val fake = Fake(onText = { flow { emit("2, 0") } })
        val prose = proseFrom(fake, draft(style = ExplainStyle.Auto))
        val first = Abridger.split(prose).first().text
        val second = Abridger.split(prose).getOrNull(1)?.text
        if (second != null) {
            assertTrue("order was not the book's", page.indexOf(first) < page.indexOf(second))
        }
    }

    @Test
    fun `a shorter style asks for fewer words`() = runTest {
        val asked = mutableMapOf<ExplainStyle, Int>()
        listOf(ExplainStyle.Concise, ExplainStyle.Auto, ExplainStyle.Detailed).forEach { style ->
            var words = 0
            val fake = Fake(onText = { prompt ->
                words = Regex("""about (\d+) words""").find(prompt)?.groupValues?.get(1)?.toInt() ?: 0
                flow { emit(GOOD_PROSE) }
            })
            proseFrom(fake, draft(style = style))
            asked[style] = words
        }
        assertTrue("Concise asked for nothing", (asked[ExplainStyle.Concise] ?: 0) > 0)
        assertTrue(
            "Concise ${asked[ExplainStyle.Concise]} should be under Auto ${asked[ExplainStyle.Auto]}",
            asked[ExplainStyle.Concise]!! < asked[ExplainStyle.Auto]!!,
        )
        assertTrue(
            "Auto ${asked[ExplainStyle.Auto]} should be under Detailed ${asked[ExplainStyle.Detailed]}",
            asked[ExplainStyle.Auto]!! < asked[ExplainStyle.Detailed]!!,
        )
    }

    @Test
    fun `a list style is not rejected for looking like a list`() = runTest {
        // Bullets and Steps fail every rule written for continuous narrative, so judging
        // them by those rules threw away exactly the output that was asked for.
        val list = "- Fortune is shaped like raw material.\n" +
            "- A rare intelligence drives discovery.\n" +
            "- It shows up under pressure and deadline."
        val fake = Fake(onText = { flow { emit(list) } })
        val prose = proseFrom(fake, draft(style = ExplainStyle.Bullets))
        assertTrue("the list was discarded:\n$prose", prose.contains("Fortune is shaped"))
    }

}
