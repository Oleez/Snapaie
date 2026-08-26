package com.snapaie.android.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Where a page's text came from, so the UI can say what happened. */
enum class TextSource { MODEL, NONE }

data class PageText(val text: String, val source: TextSource)

/**
 * Reads the text off a page.
 *
 * There is one reader now. The app used to run a text recogniser first and fall back to the
 * model when the result looked wrong, which sounds prudent and was not: the recogniser
 * cannot read handwriting at all, it fails by returning fragments rather than nothing, and
 * judging its output well enough to know when to distrust it turned out to be its own
 * unsolved problem. Two readers meant two failure modes and a rule for choosing between
 * them that was wrong at least as often as the recogniser was.
 *
 * So the model reads the page. Slower, and the only thing that can read a handwritten one.
 */
class PageTextExtractor(
    private val context: Context,
    private val pageReader: PageReader,
) {

    /**
     * Transcribes [uri], scaling it down first so the encoder gets one page rather than
     * twelve megapixels of grain.
     */
    suspend fun extract(uri: Uri): PageText = withContext(Dispatchers.IO) {
        val path = localImagePath(uri) ?: return@withContext PageText("", TextSource.NONE)
        val text = pageReader.readFile(path).trim()
        PageText(text, if (text.isBlank()) TextSource.NONE else TextSource.MODEL)
    }

    /** True when a photograph can be read at all right now. */
    fun canRead(): Boolean = pageReader.canRead()

    /**
     * A local copy of [uri] the model can read from, scaled down first.
     *
     * A phone camera produces twelve megapixels. A vision encoder does not want them: it
     * tiles what it is given, and every tile is more tokens, more memory and more time —
     * for a page of text, all spent resolving grain rather than letters. Capping the long
     * edge keeps one page to roughly one tile, which is the difference between a request
     * that fits in the context window and one that overruns it.
     */
    suspend fun localImagePath(uri: Uri): String? = withContext(Dispatchers.IO) {
        val source = localPathFor(uri) ?: return@withContext null
        runCatching { downscale(source) }.getOrDefault(source)
    }

    private fun downscale(path: String): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        if (longEdge <= 0 || longEdge <= MAX_VISION_EDGE) return path

        var sample = 1
        while (longEdge / (sample * 2) >= MAX_VISION_EDGE) sample *= 2
        val bitmap = BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return path

        val scale = MAX_VISION_EDGE.toFloat() / maxOf(bitmap.width, bitmap.height)
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

        val target = File(context.cacheDir, "page-vision-${System.currentTimeMillis()}.jpg")
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

    private companion object {
        /**
         * Long edge handed to the vision encoder. Enough to read body text on a book page,
         * small enough to stay near a single tile.
         */
        const val MAX_VISION_EDGE = 1024


    }
}
