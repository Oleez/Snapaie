package com.snapaie.android.data.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.snapaie.android.data.ocr.OcrProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Offline PDF text extraction: PdfRenderer renders each page to a bitmap, then
 * the existing ML Kit OCR reads it. Handles both text-layer and scanned PDFs
 * without a heavyweight PDF parsing library.
 */
class PdfTextExtractor(
    private val context: Context,
    private val ocrProcessor: OcrProcessor,
) {

    data class PageResult(val pageIndex: Int, val text: String)

    suspend fun pageCount(uri: Uri): Int = withContext(Dispatchers.IO) {
        openRenderer(uri)?.use { it.pageCount } ?: 0
    }

    /**
     * Extracts text from up to [maxPages] pages, invoking [onPage] as each page
     * completes so the UI can render progressively.
     */
    suspend fun extract(
        uri: Uri,
        maxPages: Int,
        onPage: suspend (PageResult) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        val renderer = openRenderer(uri) ?: return@withContext ""
        val builder = StringBuilder()
        renderer.use { pdf ->
            val pages = minOf(pdf.pageCount, maxPages)
            for (index in 0 until pages) {
                pdf.openPage(index).use { page ->
                    val scale = RENDER_WIDTH.toFloat() / page.width
                    val height = (page.height * scale).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(RENDER_WIDTH, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val text = runCatching { ocrProcessor.extractText(bitmap) }.getOrDefault("")
                    bitmap.recycle()
                    if (text.isNotBlank()) {
                        builder.appendLine(text)
                        builder.appendLine()
                    }
                    onPage(PageResult(index, text))
                }
            }
        }
        builder.toString().trim()
    }

    private fun openRenderer(uri: Uri): PdfRenderer? = runCatching {
        val descriptor: ParcelFileDescriptor? = context.contentResolver.openFileDescriptor(uri, "r")
        descriptor?.let { PdfRenderer(it) }
    }.getOrNull()

    companion object {
        /**
         * How much of a PDF is read.
         *
         * These were 5 and 60, which made every real book look broken: a novel imported at
         * five pages and stopped, with nothing on screen to say a limit had been hit. An
         * app that promises to shorten a five-hundred-page book cannot refuse to open one.
         *
         * The free tier is still a taste rather than the whole thing, but it is now enough
         * pages to be a chapter instead of a fragment — and Pro has no ceiling worth
         * hitting, because the point of Pro is the whole book.
         */
        const val FREE_PAGE_LIMIT = 40
        const val PRO_PAGE_LIMIT = 5_000
        private const val RENDER_WIDTH = 1240
    }
}
