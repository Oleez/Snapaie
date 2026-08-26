package com.snapaie.android.data.ocr

import android.content.Context
import android.graphics.Bitmap
import com.snapaie.android.data.ai.ModelSessionManager
import com.snapaie.android.domain.scan.PromptSource
import com.snapaie.android.domain.scan.Transcription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Reads the text off a page by looking at it.
 *
 * This replaces the text recogniser the app used to run first. A recogniser matches printed
 * glyphs: it is fast and it is very good at a flat, well-lit, printed page — and it cannot
 * read handwriting at all, returning either nothing or a confident scattering of wrong
 * characters. It also failed quietly, which is worse than failing loudly, because a
 * confident condensation of garbage looks exactly like a good one.
 *
 * A model that looks at the image reads the page instead of the glyphs: handwriting, a
 * curved spine, a column that wraps oddly, a form filled in by hand. It costs a great deal
 * more time than the recogniser did, and it is the only thing here that can do the job at
 * all, so the trade is worth making.
 *
 * The consequence to be honest about: with no model installed there is no way to get text
 * out of a photograph. Callers get a blank string and must say so.
 */
class PageReader(
    private val context: Context,
    private val sessionManager: ModelSessionManager,
    private val prompts: PromptSource,
) {

    /** True when a photograph can be read at all right now. */
    fun canRead(): Boolean = sessionManager.isModelInstalled()

    /** Transcribes the image at [path], or returns blank when it could not be read. */
    suspend fun readFile(path: String): String = withContext(Dispatchers.IO) {
        if (!canRead() || !File(path).isFile) return@withContext ""
        val prompt = runCatching { prompts.read(TRANSCRIBE_PROMPT) }.getOrDefault("")
        if (prompt.isBlank()) return@withContext ""

        val builder = StringBuilder()
        withTimeoutOrNull(READ_TIMEOUT_MS) {
            runCatching {
                sessionManager.streamWithImage(prompt, path, MAX_TOKENS).collect { builder.append(it) }
            }
        }
        Transcription.clean(builder.toString())
    }

    /** Transcribes [bitmap] by writing it somewhere the engine can open it. */
    suspend fun readBitmap(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        if (!canRead()) return@withContext ""
        val file = File(context.cacheDir, "page-read-${System.nanoTime()}.jpg")
        val written = runCatching {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            true
        }.getOrDefault(false)
        if (!written) return@withContext ""
        try {
            readFile(file.absolutePath)
        } finally {
            file.delete()
        }
    }

    private companion object {
        const val TRANSCRIBE_PROMPT = "prompts/transcribe.md"

        /** A transcription is the whole page, so it needs far more room than a reply. */
        const val MAX_TOKENS = 1_400

        /**
         * Reading an image runs the encoder before a single word is generated, which on a
         * phone is most of the wait. Generous, but finite: a page that will not come back
         * has to become "could not be read" rather than a spinner nobody can escape.
         */
        const val READ_TIMEOUT_MS = 150_000L
    }
}
