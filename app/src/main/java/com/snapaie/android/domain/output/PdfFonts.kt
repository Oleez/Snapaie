package com.snapaie.android.domain.output

import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font

/**
 * Text metrics and character safety for the built-in PDF fonts.
 *
 * The standard 14 fonts avoid shipping a typeface in the APK, but they are encoded in
 * WinAnsi, which covers Western European text and nothing else. Book prose is full of
 * characters just outside plain ASCII — curly quotes, em dashes, ellipses — and WinAnsi
 * does have those; what it does not have is Greek, Cyrillic, CJK or most of the symbol
 * range, and a single unmapped character makes PDFBox throw *while writing the page*,
 * which would fail an export at the very end of a job that took all night.
 *
 * So every string is filtered before it is measured or drawn, and the decision per
 * character is cached — a 50,000-word book asks the same questions tens of thousands of
 * times.
 */
class PdfFonts(
    val regular: PDFont = PDType1Font.TIMES_ROMAN,
    val bold: PDFont = PDType1Font.TIMES_BOLD,
) {

    private val supported = HashMap<Char, String>()

    val textWidth = TextWidth { text, sizePt, isBold ->
        val font = if (isBold) bold else regular
        runCatching { font.getStringWidth(sanitise(text)) / 1000f * sizePt }
            .getOrDefault(text.length * sizePt * 0.5f)
    }

    /** [text] with every character the font cannot draw replaced by something it can. */
    fun sanitise(text: String): String {
        val builder = StringBuilder(text.length)
        text.forEach { ch -> builder.append(replacementFor(ch)) }
        return builder.toString()
    }

    private fun replacementFor(ch: Char): String = supported.getOrPut(ch) {
        if (ch == '\n' || ch == '\r' || ch == '\t') return@getOrPut " "
        if (canEncode(ch)) return@getOrPut ch.toString()
        val fallback = ASCII_FALLBACKS[ch]
        when {
            fallback != null && fallback.all { canEncode(it) } -> fallback
            else -> ""
        }
    }

    private fun canEncode(ch: Char): Boolean =
        runCatching { regular.getStringWidth(ch.toString()); true }.getOrDefault(false)

    private companion object {
        /**
         * Only for characters WinAnsi genuinely lacks. Anything it already has (curly
         * quotes, dashes, the ellipsis) is left exactly as the book wrote it.
         */
        val ASCII_FALLBACKS: Map<Char, String> = mapOf(
            '\u2032' to "'", '\u2033' to "\"",
            '\u00A0' to " ", '\u2007' to " ", '\u202F' to " ", '\u2009' to " ", '\u200A' to " ",
            '\u200B' to "", '\u200C' to "", '\u200D' to "", '\uFEFF' to "",
            '\u2010' to "-", '\u2011' to "-", '\u2012' to "-", '\u2015' to "-",
            '\u2044' to "/", '\u2212' to "-", '\u00D7' to "x",
            '\u0152' to "OE", '\u0153' to "oe",
            '\u2190' to "<-", '\u2192' to "->", '\u2194' to "<->",
            '\u00BD' to "1/2", '\u00BC' to "1/4", '\u00BE' to "3/4",
        )
    }
}
