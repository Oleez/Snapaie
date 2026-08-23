package com.snapaie.android.domain.output

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Writes a laid-out book to a real PDF.
 *
 * PDFBox rather than the platform's `android.graphics.pdf.PdfDocument` for one reason: the
 * platform writer can draw a table of contents but cannot make it *work*. It has no API
 * for outline bookmarks and none for link annotations, so the contents page would be a
 * list of numbers the reader has to scroll to by hand — in a 150-page book, every time.
 */
class PdfBookWriter(private val fonts: PdfFonts = PdfFonts()) {

    /** The measurer the layout engine must use, so pagination matches what gets drawn. */
    val textWidth: TextWidth get() = fonts.textWidth

    suspend fun write(
        layout: BookLayout,
        spec: PageSpec,
        title: String,
        author: String,
        target: File,
    ): File = withContext(Dispatchers.IO) {
        PDDocument().use { document ->
            document.documentInformation.apply {
                setTitle(title)
                if (author.isNotBlank()) setAuthor(author)
                producer = "snapaie"
            }

            val pages = layout.pages.map { PDPage(PDRectangle(spec.widthPt, spec.heightPt)) }
            pages.forEach { document.addPage(it) }

            // Images are cached by path: a figure repeated across pages should be one
            // object in the file, not one per placement.
            val imageCache = HashMap<String, PDImageXObject?>()
            val chapterPages = HashMap<Long, PDPage>()

            layout.pages.forEachIndexed { index, page ->
                coroutineContext.ensureActive()
                page.chapterStarts.forEach { chapterId -> chapterPages[chapterId] = pages[index] }
                drawPage(document, pages[index], page, spec, imageCache)
            }

            addLinks(layout, pages, spec, chapterPages)
            addOutline(document, layout, chapterPages)

            target.parentFile?.mkdirs()
            document.save(target)
        }
        target
    }

    private fun drawPage(
        document: PDDocument,
        pdPage: PDPage,
        page: LaidOutPage,
        spec: PageSpec,
        imageCache: HashMap<String, PDImageXObject?>,
    ) {
        PDPageContentStream(document, pdPage).use { stream ->
            page.images.forEach { image ->
                val xObject = imageCache.getOrPut(image.path) {
                    runCatching { PDImageXObject.createFromFile(image.path, document) }.getOrNull()
                } ?: return@forEach
                runCatching {
                    stream.drawImage(
                        xObject,
                        image.xPt,
                        // Layout measures from the top; PDF space runs from the bottom.
                        spec.heightPt - image.yPt - image.heightPt,
                        image.widthPt,
                        image.heightPt,
                    )
                }
            }

            page.lines.forEach { line ->
                val text = fonts.sanitise(line.text)
                if (text.isBlank()) return@forEach
                runCatching {
                    stream.beginText()
                    stream.setFont(if (line.bold) fonts.bold else fonts.regular, line.sizePt)
                    stream.newLineAtOffset(line.xPt, spec.heightPt - line.yPt)
                    stream.showText(text)
                    stream.endText()
                }
            }
        }
    }

    /** Makes each contents row jump to its chapter. */
    private fun addLinks(
        layout: BookLayout,
        pages: List<PDPage>,
        spec: PageSpec,
        chapterPages: Map<Long, PDPage>,
    ) {
        layout.pages.forEachIndexed { index, page ->
            if (page.links.isEmpty()) return@forEachIndexed
            val host = pages[index]
            val annotations = page.links.mapNotNull { link ->
                val destinationPage = chapterPages[link.chapterId] ?: return@mapNotNull null
                PDAnnotationLink().apply {
                    rectangle = PDRectangle(
                        link.xPt,
                        spec.heightPt - link.yPt - link.heightPt,
                        link.widthPt,
                        link.heightPt,
                    )
                    action = PDActionGoTo().apply {
                        destination = PDPageFitWidthDestination().apply { this.page = destinationPage }
                    }
                    // A visible box around every contents row would look like a web page.
                    borderStyle = PDBorderStyleDictionary().apply { width = 0f }
                }
            }
            if (annotations.isNotEmpty()) runCatching { host.annotations.addAll(annotations) }
        }
    }

    /** Bookmarks, so the reader's PDF app shows a real chapter sidebar. */
    private fun addOutline(
        document: PDDocument,
        layout: BookLayout,
        chapterPages: Map<Long, PDPage>,
    ) {
        if (layout.toc.isEmpty()) return
        val outline = PDDocumentOutline()
        document.documentCatalog.documentOutline = outline

        layout.toc.forEach { entry ->
            val page = chapterPages[entry.chapterId] ?: return@forEach
            val item = PDOutlineItem().apply {
                title = fonts.sanitise(entry.title).ifBlank { "Chapter" }
                destination = PDPageFitWidthDestination().apply { this.page = page }
            }
            outline.addLast(item)
        }
        runCatching { outline.openNode() }
    }
}
