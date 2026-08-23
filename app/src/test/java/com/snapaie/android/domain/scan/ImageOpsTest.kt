package com.snapaie.android.domain.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageOpsTest {

    private val width = 120
    private val height = 80

    /**
     * A page photographed under a lamp: paper and ink both sit in a narrow band, and a
     * strong left-to-right gradient means the right edge is far darker than the left.
     * This is the case a global threshold cannot survive.
     */
    private fun unevenlyLitPage(): IntArray = IntArray(width * height) { index ->
        val x = index % width
        val y = index / width
        val falloff = 90 - (x * 70 / width)
        val isInk = (y / 8) % 2 == 0 && (x % 20) < 9
        val base = if (isInk) falloff - 35 else falloff + 35
        val value = base.coerceIn(0, 255)
        ImageOps.argb(255, value, value, value)
    }

    private fun isInkAt(x: Int, y: Int) = (y / 8) % 2 == 0 && (x % 20) < 9

    @Test
    fun `auto levels stretches a narrow band to the full range`() {
        val out = ImageOps.autoLevels(unevenlyLitPage())
        val luma = out.map { ImageOps.luminance(it) }
        assertTrue("darkest pixel is still ${luma.min()}", luma.min() < 20)
        assertTrue("brightest pixel is only ${luma.max()}", luma.max() > 235)
    }

    @Test
    fun `auto levels leaves a flat image alone instead of amplifying noise`() {
        val flat = IntArray(width * height) { ImageOps.argb(255, 128, 128, 128) }
        assertTrue(ImageOps.autoLevels(flat).all { ImageOps.luminance(it) == 128 })
    }

    @Test
    fun `auto levels corrects a colour cast`() {
        // Tungsten light: the blue channel is compressed into the bottom of its range.
        val warm = IntArray(width * height) { index ->
            val v = 60 + (index % 40)
            ImageOps.argb(255, v + 60, v + 30, v)
        }
        val out = ImageOps.autoLevels(warm)
        val red = out.map { ImageOps.redOf(it) }
        val blue = out.map { ImageOps.blueOf(it) }
        assertEquals("channels should end up on the same scale", red.min(), blue.min())
        assertEquals(red.max(), blue.max())
    }

    @Test
    fun `adaptive threshold keeps text on both the bright and the dark side of a gradient`() {
        val out = ImageOps.adaptiveThreshold(unevenlyLitPage(), width, height)

        var correct = 0
        var total = 0
        for (y in 4 until height - 4) {
            for (x in 4 until width - 4) {
                val black = ImageOps.luminance(out[y * width + x]) < 128
                if (black == isInkAt(x, y)) correct++
                total++
            }
        }
        assertTrue("only $correct of $total pixels classified correctly", correct > total * 0.9)
    }

    @Test
    fun `a global threshold would have failed the same page - both halves survive`() {
        val out = ImageOps.adaptiveThreshold(unevenlyLitPage(), width, height)
        // The bright left edge and the dark right edge must both retain ink and paper.
        listOf(4 until 30, (width - 30) until (width - 4)).forEach { range ->
            var black = 0
            var white = 0
            for (y in 4 until height - 4) {
                for (x in range) {
                    if (ImageOps.luminance(out[y * width + x]) < 128) black++ else white++
                }
            }
            assertTrue("a region came out solid: $black black, $white white", black > 0 && white > 0)
        }
    }

    @Test
    fun `threshold output is strictly black or white`() {
        ImageOps.adaptiveThreshold(unevenlyLitPage(), width, height).forEach { pixel ->
            val luma = ImageOps.luminance(pixel)
            assertTrue("got a grey pixel: $luma", luma == 0 || luma == 255)
        }
    }

    @Test
    fun `grayscale removes colour but keeps brightness ordering`() {
        val source = intArrayOf(
            ImageOps.argb(255, 200, 30, 30),
            ImageOps.argb(255, 30, 200, 30),
            ImageOps.argb(255, 10, 10, 10),
        )
        val out = ImageOps.grayscale(source)
        out.forEach { assertEquals(ImageOps.redOf(it), ImageOps.blueOf(it)) }
        assertTrue(ImageOps.luminance(out[1]) > ImageOps.luminance(out[0]))
        assertTrue(ImageOps.luminance(out[0]) > ImageOps.luminance(out[2]))
    }

    @Test
    fun `degenerate sizes are returned untouched rather than crashing`() {
        val empty = IntArray(0)
        assertEquals(0, ImageOps.autoLevels(empty).size)
        assertEquals(0, ImageOps.adaptiveThreshold(empty, 0, 0).size)
        val tiny = IntArray(4) { ImageOps.argb(255, 10, 10, 10) }
        assertEquals(4, ImageOps.sharpen(tiny, 2, 2).size)
        // Fewer pixels than the stated dimensions must not read off the end.
        assertEquals(4, ImageOps.adaptiveThreshold(tiny, 100, 100).size)
    }

    @Test
    fun `magic colour preserves size and alpha`() {
        val source = unevenlyLitPage()
        val out = ImageOps.magicColour(source, width, height)
        assertEquals(source.size, out.size)
        out.forEach { assertEquals(255, ImageOps.alphaOf(it)) }
    }
}
