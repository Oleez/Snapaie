package com.snapaie.android.data.ingest

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.snapaie.android.data.ocr.OcrProcessor
import com.snapaie.android.domain.book.ChapterHint
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Reads a PDF into one text stream, a chapter outline and a set of images.
 *
 * Hybrid by design. A publisher PDF has a real text layer, and extracting it keeps
 * spelling, punctuation and proper nouns exact — which matters enormously when the next
 * stage has to retell a story without mangling character names. A scanned PDF has no text
 * layer at all, so those pages fall through to the existing render-and-OCR path
 * ([com.snapaie.android.data.pdf.PdfTextExtractor]'s approach) rather than producing
 * nothing. Most real books need both, sometimes within the same file.
 */
class PdfIngestor(
    private val context: Context,
    private val ocrProcessor: OcrProcessor,
) {

    suspend fun ingest(
        source: File,
        imageDir: File,
        onProgress: suspend (IngestProgress) -> Unit = {},
    ): IngestedDocument = withContext(Dispatchers.IO) {
        imageDir.mkdirs()
        PDDocument.load(source.inputStream()).use { document ->
            val pageCount = document.numberOfPages
            val builder = StringBuilder()
            val pageStarts = ArrayList<Int>(pageCount)
            val images = mutableListOf<IngestedImage>()
            var ocrPages = 0

            // OCR needs the platform renderer, which wants its own descriptor on the file.
            val renderer = runCatching {
                PdfRenderer(ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY))
            }.getOrNull()

            try {
                for (index in 0 until pageCount) {
                    coroutineContext.ensureActive()
                    pageStarts += builder.length

                    val layerText = runCatching { extractPageText(document, index + 1) }
                        .getOrDefault("")
                        .trim()

                    val usedOcr = layerText.length < MIN_TEXT_LAYER_CHARS
                    val pageText = if (usedOcr) {
                        ocrPages++
                        renderer?.let { ocrPage(it, index) }.orEmpty()
                    } else {
                        layerText
                    }

                    builder.append(pageText)
                    builder.append(PAGE_SEPARATOR)

                    runCatching {
                        images += extractImages(
                            page = document.getPage(index),
                            pageIndex = index,
                            anchorChar = pageStarts.last(),
                            hasTextLayer = !usedOcr,
                            imageDir = imageDir,
                        )
                    }

                    onProgress(IngestProgress(index + 1, pageCount, usedOcr))
                }
            } finally {
                runCatching { renderer?.close() }
            }

            val text = builder.toString()
            IngestedDocument(
                title = document.documentInformation?.title?.takeIf { it.isNotBlank() }
                    ?: source.nameWithoutExtension,
                author = document.documentInformation?.author.orEmpty(),
                text = text,
                pageCount = pageCount,
                pageStartChars = pageStarts,
                chapterHints = readOutline(document, pageStarts),
                images = images,
                ocrPageCount = ocrPages,
            )
        }
    }

    private fun extractPageText(document: PDDocument, pageNumber: Int): String =
        PDFTextStripper().apply {
            startPage = pageNumber
            endPage = pageNumber
            sortByPosition = true
        }.getText(document)

    private suspend fun ocrPage(renderer: PdfRenderer, index: Int): String {
        if (index >= renderer.pageCount) return ""
        return renderer.openPage(index).use { page ->
            val scale = OCR_RENDER_WIDTH.toFloat() / page.width
            val height = (page.height * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(OCR_RENDER_WIDTH, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val text = runCatching { ocrProcessor.extractText(bitmap) }.getOrDefault("")
            bitmap.recycle()
            text
        }
    }

    /**
     * Images worth keeping from one page.
     *
     * Two things get filtered out, both of which would otherwise dominate a real book:
     * tiny decorations (rules, bullets, logos repeated on every page), and full-page
     * images on pages that also carry text — those are scanned backgrounds or watermarks
     * sitting behind the words, not figures.
     */
    private fun extractImages(
        page: PDPage,
        pageIndex: Int,
        anchorChar: Int,
        hasTextLayer: Boolean,
        imageDir: File,
    ): List<IngestedImage> {
        val resources = page.resources ?: return emptyList()
        val pageArea = (page.mediaBox?.width ?: 0f) * (page.mediaBox?.height ?: 0f)
        val kept = mutableListOf<IngestedImage>()
        var order = 0

        for (name in resources.xObjectNames) {
            if (!resources.isImageXObject(name)) continue
            val xObject = runCatching { resources.getXObject(name) }.getOrNull() as? PDImageXObject ?: continue
            if (xObject.width < MIN_IMAGE_PX || xObject.height < MIN_IMAGE_PX) continue

            val bitmap = runCatching { xObject.image }.getOrNull() ?: continue
            try {
                val coversPage = pageArea > 0f &&
                    bitmap.width.toFloat() * bitmap.height >= pageArea * FULL_PAGE_AREA_RATIO
                if (coversPage && hasTextLayer) continue

                val file = File(imageDir, "p%04d_%02d.jpg".format(pageIndex + 1, order))
                val saved = writeDownsampled(bitmap, file) ?: continue
                kept += IngestedImage(
                    path = file.absolutePath,
                    srcPage = pageIndex + 1,
                    srcChar = anchorChar,
                    widthPx = saved.first,
                    heightPx = saved.second,
                )
                order++
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
        return kept
    }

    /**
     * Writes [bitmap] scaled to fit [MAX_IMAGE_EDGE]. A 500-page illustrated book can carry
     * hundreds of figures at print resolution, and keeping those at full size would cost
     * more storage than the model does.
     */
    private fun writeDownsampled(bitmap: Bitmap, target: File): Pair<Int, Int>? {
        val longEdge = maxOf(bitmap.width, bitmap.height)
        val scale = if (longEdge > MAX_IMAGE_EDGE) MAX_IMAGE_EDGE.toFloat() / longEdge else 1f
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)

        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, width, height, true) else bitmap
        return try {
            target.outputStream().use { out ->
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) return null
            }
            width to height
        } catch (error: Exception) {
            target.delete()
            null
        } finally {
            if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
        }
    }

    /**
     * Chapter hints from the PDF outline, flattened to top level and mapped onto character
     * offsets via the page each bookmark points at. Nested sub-headings are ignored: a
     * chapter is the granularity the reader navigates by, and taking every level would
     * shred the book into fragments.
     */
    private fun readOutline(document: PDDocument, pageStarts: List<Int>): List<ChapterHint> {
        val outline = runCatching { document.documentCatalog?.documentOutline }.getOrNull()
            ?: return emptyList()
        val hints = mutableListOf<ChapterHint>()
        var item: PDOutlineItem? = runCatching { outline.firstChild }.getOrNull()

        while (item != null && hints.size < MAX_OUTLINE_ENTRIES) {
            val current = item
            val pageIndex = runCatching {
                current.findDestinationPage(document)?.let { document.pages.indexOf(it) }
            }.getOrNull() ?: -1

            if (pageIndex in pageStarts.indices) {
                val title = runCatching { current.title }.getOrNull().orEmpty().trim()
                hints += ChapterHint(
                    title = title.ifBlank { "Chapter ${hints.size + 1}" },
                    startChar = pageStarts[pageIndex],
                    page = pageIndex + 1,
                )
            }
            item = runCatching { current.nextSibling }.getOrNull()
        }
        return hints.distinctBy { it.startChar }
    }

    private companion object {
        /** Below this a page is treated as scanned and sent to OCR. */
        const val MIN_TEXT_LAYER_CHARS = 40
        const val PAGE_SEPARATOR = "\n\n"
        const val OCR_RENDER_WIDTH = 1240
        const val MIN_IMAGE_PX = 100
        const val FULL_PAGE_AREA_RATIO = 0.95f
        const val MAX_IMAGE_EDGE = 1600
        const val JPEG_QUALITY = 85
        const val MAX_OUTLINE_ENTRIES = 500
    }
}
