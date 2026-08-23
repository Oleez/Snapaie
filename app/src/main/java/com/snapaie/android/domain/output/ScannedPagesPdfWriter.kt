package com.snapaie.android.domain.output

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/**
 * Binds a stack of photographed pages into a PDF.
 *
 * Each page is sized to the image rather than forced onto a fixed sheet, because a book
 * page photographed at an angle and cropped is not A4 and letterboxing it would waste half
 * the file on white margins. The one exception is [fitToPage], for when the user actually
 * wants something printable.
 */
class ScannedPagesPdfWriter {

    suspend fun write(
        pages: List<File>,
        target: File,
        fitToPage: PageSpec? = null,
    ): File = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        PDDocument().use { document ->
            pages.filter { it.isFile }.forEach { file ->
                coroutineContext.ensureActive()
                val image = runCatching { PDImageXObject.createFromFile(file.absolutePath, document) }
                    .getOrNull() ?: return@forEach

                val (pageWidth, pageHeight) = when (fitToPage) {
                    null -> pointsFor(image.width, image.height)
                    else -> fitToPage.widthPt to fitToPage.heightPt
                }
                val page = PDPage(PDRectangle(pageWidth, pageHeight))
                document.addPage(page)

                PDPageContentStream(document, page).use { stream ->
                    val margin = fitToPage?.marginPt ?: 0f
                    val available = (pageWidth - margin * 2) to (pageHeight - margin * 2)
                    val scale = min(available.first / image.width, available.second / image.height)
                    val drawWidth = image.width * scale
                    val drawHeight = image.height * scale
                    runCatching {
                        stream.drawImage(
                            image,
                            (pageWidth - drawWidth) / 2f,
                            (pageHeight - drawHeight) / 2f,
                            drawWidth,
                            drawHeight,
                        )
                    }
                }
            }
            if (document.numberOfPages == 0) error("None of those pages could be read.")
            document.save(target)
        }
        target
    }

    /** Pixels to points at a sane scan resolution, so the page is a believable size. */
    private fun pointsFor(widthPx: Int, heightPx: Int): Pair<Float, Float> {
        val scale = POINTS_PER_INCH / SCAN_DPI
        return widthPx * scale to heightPx * scale
    }

    private companion object {
        const val POINTS_PER_INCH = 72f
        const val SCAN_DPI = 200f
    }
}
