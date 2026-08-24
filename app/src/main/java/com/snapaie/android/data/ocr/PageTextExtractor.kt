package com.snapaie.android.data.ocr

import android.content.Context
import android.net.Uri
import com.snapaie.android.data.ai.ModelSessionManager
import com.snapaie.android.domain.scan.TextQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Where a page's text came from, so the UI can say what happened. */
enum class TextSource { RECOGNISER, MODEL, NONE }

data class PageText(val text: String, val source: TextSource)

/**
 * Reads the text off a page, with a second opinion when the first is poor.
 *
 * The fast text recogniser handles a flat, well-lit, printed page better than anything and
 * costs milliseconds, so it always goes first. What it cannot do is cope with a curved
 * spine, a column that wraps oddly, or a page where the lighting ate the thin strokes — and
 * it fails *quietly*, returning fragments rather than nothing, which is how a confident
 * condensation of garbage ends up in front of the user.
 *
 * So its output is judged. When it looks wrong and the offline model is available, the model
 * is asked to read the picture itself: far slower, but it sees layout and context rather
 * than isolated glyphs. Whichever answer is better wins.
 */
class PageTextExtractor(
    private val context: Context,
    private val ocrProcessor: OcrProcessor,
    private val sessionManager: ModelSessionManager,
) {

    suspend fun extract(uri: Uri): PageText = withContext(Dispatchers.IO) {
        // Recognition only. The model used to be asked to transcribe a page the recogniser
        // struggled with, which cost a whole generation before the work of condensing had
        // even started — and then a second one to condense. Since the model reads the
        // photograph directly when it condenses, this text is no longer on the path to a
        // result: it is kept for search, for word counts, and for comparing against the
        // original, none of which the user waits on.
        val recognised = runCatching { ocrProcessor.extractText(uri) }.getOrDefault("").trim()
        PageText(recognised, sourceFor(recognised))
    }

    /** A local copy of [uri] the model can read from, or null when it cannot be made. */
    suspend fun localImagePath(uri: Uri): String? = withContext(Dispatchers.IO) { localPathFor(uri) }

    private suspend fun readWithModel(path: String): String {
        val builder = StringBuilder()
        sessionManager.streamWithImage(PROMPT, path).collect { builder.append(it) }
        return clean(builder.toString())
    }

    /**
     * Strips the framing a small model wraps around a transcription. Asking for "only the
     * text" gets you the text plus an apology roughly one time in five.
     */
    private fun clean(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```").substringAfter('\n', "").substringBeforeLast("```")
        }
        listOf(
            "Here is the text", "Here's the text", "The text reads", "Transcription:",
            "The page says", "Sure,", "Certainly,",
        ).forEach { lead ->
            if (text.startsWith(lead, ignoreCase = true)) {
                text = text.removeRange(0, lead.length).trimStart(':', ' ', '\n')
            }
        }
        return text.trim()
    }

    /** The model reads from a file path, so a content URI has to be materialised first. */
    private fun localPathFor(uri: Uri): String? {
        uri.path?.let { path ->
            if (uri.scheme == "file" && File(path).isFile) return path
        }
        return runCatching {
            val target = File(context.cacheDir, "page-read-${System.currentTimeMillis()}.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            } ?: return null
            target.absolutePath
        }.getOrNull()
    }

    private fun sourceFor(text: String): TextSource =
        if (text.isBlank()) TextSource.NONE else TextSource.RECOGNISER

    private companion object {
        val PROMPT = """
            Read this page and type out its text exactly as it appears.

            Keep the original wording, spelling and punctuation. Keep paragraph breaks.
            Read in the order a person would: down a column before moving to the next one.
            Skip page numbers, running heads and footers.

            Output only the text from the page. No commentary, no description of the image,
            no explanation of what you did. If there is no readable text, output nothing.
        """.trimIndent()
    }
}
