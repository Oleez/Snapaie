package com.snapaie.android.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.snapaie.android.domain.scan.TextQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Where a page's text came from, and what to do when there isn't any.
 *
 * [NEEDS_CLOUD] is the interesting one. It does not mean the capture failed — it means the
 * recogniser produced something too poor to condense, which on a photographed page almost
 * always means handwriting. That is a page worth offering to read properly, not an error.
 */
enum class TextSource { RECOGNISER, NEEDS_CLOUD, NONE }

data class PageText(val text: String, val source: TextSource)

/**
 * Reads the text off a page.
 *
 * The recogniser does this, not the model. It matches printed glyphs natively in
 * milliseconds and costs about eleven megabytes, where a multimodal model costs two
 * gigabytes, minutes per page, and more memory than most phones have. For a printed page
 * that is an enormous price for a job a purpose-built recogniser does better.
 *
 * What a recogniser cannot do is read handwriting — not poorly, but not at all. It fails by
 * returning fragments rather than nothing, which is how a confident condensation of garbage
 * used to reach the screen. So its output is judged rather than trusted: [TextQuality] sorts
 * a real page from a handful of stray characters, and a page it cannot vouch for is marked
 * [TextSource.NEEDS_CLOUD] rather than condensed anyway.
 */
class PageTextExtractor(
    private val context: Context,
    private val ocrProcessor: OcrProcessor,
) {

    suspend fun extract(uri: Uri): PageText = withContext(Dispatchers.IO) {
        val recognised = runCatching { ocrProcessor.extractText(uri) }.getOrDefault("").trim()
        PageText(recognised, sourceFor(recognised))
    }

    /**
     * A local copy of [uri], scaled down first.
     *
     * A phone camera produces twelve megapixels; nothing downstream wants them. Capping the
     * long edge keeps the stored page small, and keeps it within what a cloud transcription
     * request can carry without paying to resolve grain rather than letters.
     */
    suspend fun localImagePath(uri: Uri): String? = withContext(Dispatchers.IO) {
        val source = localPathFor(uri) ?: return@withContext null
        runCatching { downscale(source) }.getOrDefault(source)
    }

    private fun sourceFor(text: String): TextSource = classify(text)

    private fun downscale(path: String): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        if (longEdge <= 0 || longEdge <= MAX_PAGE_EDGE) return path

        var sample = 1
        while (longEdge / (sample * 2) >= MAX_PAGE_EDGE) sample *= 2
        val bitmap = BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return path

        val scale = MAX_PAGE_EDGE.toFloat() / maxOf(bitmap.width, bitmap.height)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }

        val target = File(context.cacheDir, "page-${System.currentTimeMillis()}.jpg")
        return try {
            target.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            target.absolutePath
        } catch (error: Exception) {
            path
        } finally {
            if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /** A content URI has to be materialised before anything can open it by path. */
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

    companion object {
        /** Long edge kept for the stored page: enough to read body text, small to send. */
        private const val MAX_PAGE_EDGE = 1024

        /**
         * What the recogniser gave us, judged.
         *
         * Three outcomes, not two, and the distinction is the whole point. A blank result
         * is a page with no text on it — retaking the photo might help. A poor result is a
         * page whose text this reader cannot handle, which on a photograph means
         * handwriting, and no amount of retaking will fix that. Telling someone to try
         * again in better light when the real answer is "this reader cannot read cursive"
         * is advice that cannot work.
         *
         * Public and pure so the thresholds can be pinned by tests rather than guessed at.
         */
        fun classify(recognised: String): TextSource = when {
            recognised.isBlank() -> TextSource.NONE
            TextQuality.assess(recognised) == TextQuality.Verdict.GOOD -> TextSource.RECOGNISER
            else -> TextSource.NEEDS_CLOUD
        }
    }
}
