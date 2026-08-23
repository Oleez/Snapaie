package com.snapaie.android.domain.scan

/**
 * Page-enhancement maths on plain ARGB int arrays.
 *
 * Kept free of `android.graphics` so the algorithms can be tested directly — a threshold
 * that mangles a page under uneven light is the difference between usable OCR and
 * garbage, and that is not something to find out on a phone.
 */
object ImageOps {

    fun alphaOf(pixel: Int): Int = (pixel ushr 24) and 0xFF
    fun redOf(pixel: Int): Int = (pixel ushr 16) and 0xFF
    fun greenOf(pixel: Int): Int = (pixel ushr 8) and 0xFF
    fun blueOf(pixel: Int): Int = pixel and 0xFF

    fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** Rec. 601 luma, which is what matters for text against paper. */
    fun luminance(pixel: Int): Int =
        (redOf(pixel) * 299 + greenOf(pixel) * 587 + blueOf(pixel) * 114) / 1000

    /**
     * Auto levels: stretches each channel so the darkest [lowPercentile] and brightest
     * [highPercentile] of it are clipped flat.
     *
     * This is the "auto brightness" a document scanner does. Photographing a page under a
     * lamp gives grey paper and grey ink across a narrow slice of the range; pushing that
     * slice out to full black and full white is what makes it look scanned rather than
     * photographed. Doing it per channel corrects the colour cast from tungsten or
     * fluorescent light at the same time.
     */
    fun autoLevels(
        pixels: IntArray,
        lowPercentile: Float = 0.02f,
        highPercentile: Float = 0.98f,
    ): IntArray {
        if (pixels.isEmpty()) return pixels
        val out = IntArray(pixels.size)
        val luts = Array(3) { channel ->
            val histogram = IntArray(256)
            pixels.forEach { pixel ->
                histogram[channelValue(pixel, channel)]++
            }
            buildLut(histogram, pixels.size, lowPercentile, highPercentile)
        }
        for (index in pixels.indices) {
            val pixel = pixels[index]
            out[index] = argb(
                alphaOf(pixel),
                luts[0][redOf(pixel)],
                luts[1][greenOf(pixel)],
                luts[2][blueOf(pixel)],
            )
        }
        return out
    }

    private fun channelValue(pixel: Int, channel: Int): Int = when (channel) {
        0 -> redOf(pixel)
        1 -> greenOf(pixel)
        else -> blueOf(pixel)
    }

    private fun buildLut(histogram: IntArray, total: Int, low: Float, high: Float): IntArray {
        val lowCount = (total * low).toInt()
        val highCount = (total * high).toInt()
        var running = 0
        var min = 0
        var max = 255
        for (value in 0..255) {
            running += histogram[value]
            if (running > lowCount) { min = value; break }
        }
        running = 0
        for (value in 0..255) {
            running += histogram[value]
            if (running >= highCount) { max = value; break }
        }
        // A flat image (a blank page, a lens cap) has no range to stretch; leave it alone
        // rather than amplifying sensor noise into a field of static.
        if (max - min < MIN_USEFUL_RANGE) return IntArray(256) { it }
        val scale = 255f / (max - min)
        return IntArray(256) { value -> ((value - min) * scale).toInt().coerceIn(0, 255) }
    }

    fun grayscale(pixels: IntArray): IntArray = IntArray(pixels.size) { index ->
        val pixel = pixels[index]
        val luma = luminance(pixel)
        argb(alphaOf(pixel), luma, luma, luma)
    }

    /**
     * Bradley adaptive threshold: each pixel is compared against the mean of the window
     * around it rather than against one number for the whole page.
     *
     * A global threshold cannot cope with a photographed book. There is always a gradient
     * — the lamp is on one side, the gutter falls into shadow, the page curves away — and
     * any single cut-off either blows out the bright half or fills the dark half solid
     * black, taking the text with it. Comparing locally means the shadowed gutter is
     * thresholded against its own darkness, so the words in it survive.
     *
     * Runs on an integral image, so it costs the same regardless of window size.
     */
    fun adaptiveThreshold(
        pixels: IntArray,
        width: Int,
        height: Int,
        windowDivisor: Int = 8,
        /** How far below the local mean a pixel must fall to count as ink. */
        thresholdPercent: Int = 12,
    ): IntArray {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return pixels

        // Row-major integral image with a zero row and column, so a window sum is four
        // lookups with no bounds special-casing.
        val integral = LongArray((width + 1) * (height + 1))
        for (y in 0 until height) {
            var rowSum = 0L
            for (x in 0 until width) {
                rowSum += luminance(pixels[y * width + x])
                integral[(y + 1) * (width + 1) + (x + 1)] =
                    integral[y * (width + 1) + (x + 1)] + rowSum
            }
        }

        val half = (width / windowDivisor.coerceAtLeast(1) / 2).coerceIn(4, 64)
        val out = IntArray(pixels.size)
        val keep = 255 - thresholdPercent.coerceIn(0, 100) * 255 / 100

        for (y in 0 until height) {
            val top = (y - half).coerceAtLeast(0)
            val bottom = (y + half).coerceAtMost(height - 1)
            for (x in 0 until width) {
                val left = (x - half).coerceAtLeast(0)
                val right = (x + half).coerceAtMost(width - 1)
                val count = ((right - left + 1) * (bottom - top + 1)).toLong()
                val sum = integral[(bottom + 1) * (width + 1) + (right + 1)] -
                    integral[top * (width + 1) + (right + 1)] -
                    integral[(bottom + 1) * (width + 1) + left] +
                    integral[top * (width + 1) + left]
                val index = y * width + x
                val value = luminance(pixels[index])
                val isInk = value.toLong() * count * 255 < sum * keep
                out[index] = if (isInk) argb(alphaOf(pixels[index]), 0, 0, 0)
                else argb(alphaOf(pixels[index]), 255, 255, 255)
            }
        }
        return out
    }

    /**
     * Auto levels plus a saturation lift and a light unsharp mask: the "magic colour" look,
     * for pages with highlighter, diagrams or colour plates that a B&W pass would destroy.
     */
    fun magicColour(pixels: IntArray, width: Int, height: Int): IntArray {
        val levelled = autoLevels(pixels)
        val saturated = IntArray(levelled.size) { index ->
            val pixel = levelled[index]
            val luma = luminance(pixel)
            argb(
                alphaOf(pixel),
                luma + ((redOf(pixel) - luma) * SATURATION).toInt(),
                luma + ((greenOf(pixel) - luma) * SATURATION).toInt(),
                luma + ((blueOf(pixel) - luma) * SATURATION).toInt(),
            )
        }
        return sharpen(saturated, width, height)
    }

    /** 3x3 unsharp mask. Edges are clamped rather than wrapped. */
    fun sharpen(pixels: IntArray, width: Int, height: Int, amount: Float = 0.6f): IntArray {
        if (width < 3 || height < 3 || pixels.size < width * height) return pixels
        val out = pixels.copyOf()
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val index = y * width + x
                val centre = pixels[index]
                var r = 0
                var g = 0
                var b = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val neighbour = pixels[(y + dy) * width + (x + dx)]
                        r += redOf(neighbour)
                        g += greenOf(neighbour)
                        b += blueOf(neighbour)
                    }
                }
                out[index] = argb(
                    alphaOf(centre),
                    redOf(centre) + ((redOf(centre) - r / 9) * amount).toInt(),
                    greenOf(centre) + ((greenOf(centre) - g / 9) * amount).toInt(),
                    blueOf(centre) + ((blueOf(centre) - b / 9) * amount).toInt(),
                )
            }
        }
        return out
    }

    private const val SATURATION = 1.35f
    private const val MIN_USEFUL_RANGE = 12
}
