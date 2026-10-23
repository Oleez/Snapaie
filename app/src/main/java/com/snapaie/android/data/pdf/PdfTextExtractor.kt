package com.snapaie.android.data.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.snapaie.android.data.ocr.OcrProcessor
import com.snapaie.android.data.ocr.PageTextExtractor
import com.snapaie.android.data.ocr.TextSource
import com.snapaie.android.data.model.ScanPageInput
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Offline PDF text extraction. Text-layer pages retain their source text; scanned pages
 * are rendered and sent through the same local OCR used by the camera. The image remains
 * available as a vision fallback only when OCR cannot vouch for its transcript.
 */
class PdfTextExtractor(
    private val context: Context,
    private val ocrProcessor: OcrProcessor,
) {

    data class PageResult(val pageIndex: Int, val text: String)

    /** Page-level Snap input: native text is retained; scanned pages stay images. */
    suspend fun prepareForSnap(uri: Uri, maxPages: Int): List<ScanPageInput> =
        withContext(Dispatchers.IO) {
            val source = File(context.cacheDir, "snap-pdf-${System.nanoTime()}.pdf")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    source.outputStream().use { output -> input.copyTo(output) }
                } ?: error("That PDF could not be opened.")

                PDDocument.load(source).use { document ->
                    val descriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
                    PdfRenderer(descriptor).use { renderer ->
                        val pages = minOf(document.numberOfPages, renderer.pageCount, maxPages)
                        buildList {
                            for (index in 0 until pages) {
                                val text = PDFTextStripper().apply {
                                    startPage = index + 1
                                    endPage = index + 1
                                    sortByPosition = true
                                }.getText(document).trim()

                                if (text.length >= MIN_TEXT_LAYER_CHARS) {
                                    add(ScanPageInput(pageNumber = index + 1, text = text))
                                } else {
                                    val imagePath = renderPage(renderer, index)
                                    val recognised = runCatching {
                                        ocrProcessor.extractText(Uri.fromFile(File(imagePath))).trim()
                                    }.getOrDefault("")
                                    add(
                                        ScanPageInput(
                                            pageNumber = index + 1,
                                            text = recognised.takeIf {
                                                PageTextExtractor.classify(it) == TextSource.RECOGNISER
                                            }.orEmpty(),
                                            imagePath = imagePath,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            } finally {
                source.delete()
            }
        }

    private fun renderPage(renderer: PdfRenderer, index: Int): String =
        renderer.openPage(index).use { page ->
            val scale = minOf(1f, SNAP_RENDER_WIDTH.toFloat() / page.width)
            val width = (page.width * scale).toInt().coerceAtLeast(1)
            val height = (page.height * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val image = File(context.cacheDir, "snap-pdf-page-${System.nanoTime()}-$index.jpg")
                check(image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }) {
                    "PDF page image could not be prepared."
                }
                image.absolutePath
            } finally {
                bitmap.recycle()
            }
        }

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
        private const val SNAP_RENDER_WIDTH = 1240
        private const val MIN_TEXT_LAYER_CHARS = 24
        private const val RENDER_WIDTH = 1240
    }
}
