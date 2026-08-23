package com.snapaie.android.domain.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** How a captured page is cleaned up. */
enum class ScanFilter(val label: String, val description: String) {
    AUTO("Auto", "Even out the lighting and lift the contrast. Best for most photos."),
    GREYSCALE("Greyscale", "Neutral grey, kinder to shadows than pure black and white."),
    BLACK_WHITE("B&W", "Pure ink on white. Sharpest text, smallest file."),
    MAGIC("Magic colour", "Keeps highlighter, diagrams and colour plates."),
    ORIGINAL("Original", "Leave the photo exactly as taken."),
    ;

    companion object {
        fun fromStored(value: String?): ScanFilter =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: AUTO
    }
}

/**
 * Applies a [ScanFilter] to a captured page.
 *
 * This exists as our own code rather than relying on the scanner's built-in filters so
 * that a photographed page, a gallery import and a rendered PDF page all come out looking
 * the same. A book scanned over several sessions should not have three different looks in
 * it depending on which route each page took in.
 */
class ScanEnhancer {

    suspend fun enhanceFile(source: File, filter: ScanFilter, target: File = source): File =
        withContext(Dispatchers.IO) {
            if (filter == ScanFilter.ORIGINAL && source == target) return@withContext source
            val bitmap = BitmapFactory.decodeFile(source.absolutePath)
                ?: return@withContext source
            val enhanced = try {
                enhance(bitmap, filter)
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
            runCatching {
                target.parentFile?.mkdirs()
                target.outputStream().use { out ->
                    enhanced.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                }
            }
            if (!enhanced.isRecycled) enhanced.recycle()
            target
        }

    fun enhance(source: Bitmap, filter: ScanFilter): Bitmap {
        if (filter == ScanFilter.ORIGINAL) return source.copy(Bitmap.Config.ARGB_8888, false) ?: source
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        val processed = when (filter) {
            ScanFilter.AUTO -> ImageOps.sharpen(ImageOps.autoLevels(pixels), width, height, amount = 0.35f)
            ScanFilter.GREYSCALE -> ImageOps.grayscale(ImageOps.autoLevels(pixels))
            ScanFilter.BLACK_WHITE -> ImageOps.adaptiveThreshold(pixels, width, height)
            ScanFilter.MAGIC -> ImageOps.magicColour(pixels, width, height)
            ScanFilter.ORIGINAL -> pixels
        }
        return Bitmap.createBitmap(processed, width, height, Bitmap.Config.ARGB_8888)
    }

    private companion object {
        const val JPEG_QUALITY = 88
    }
}
