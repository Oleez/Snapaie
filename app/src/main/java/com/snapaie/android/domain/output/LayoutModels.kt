package com.snapaie.android.domain.output

/** Page geometry, in PDF points (1/72 inch). */
data class PageSpec(
    val widthPt: Float,
    val heightPt: Float,
    val marginPt: Float = 54f,
    val bodySizePt: Float = 11f,
    val headingSizePt: Float = 18f,
    val captionSizePt: Float = 8.5f,
    val lineHeightRatio: Float = 1.42f,
    val paragraphGapPt: Float = 5f,
) {
    val textWidthPt: Float get() = widthPt - marginPt * 2
    val textHeightPt: Float get() = heightPt - marginPt * 2
    val bodyLeadingPt: Float get() = bodySizePt * lineHeightRatio
    val headingLeadingPt: Float get() = headingSizePt * lineHeightRatio

    companion object {
        /** Trade paperback. The default, and what [CondenseTarget.WORDS_PER_PAGE] is tuned to. */
        val SIX_BY_NINE = PageSpec(widthPt = 432f, heightPt = 648f)
        val A5 = PageSpec(widthPt = 419.5f, heightPt = 595.3f)
        val A4 = PageSpec(widthPt = 595.3f, heightPt = 841.9f, marginPt = 64f, bodySizePt = 11.5f)

        fun named(name: String): PageSpec = when (name.uppercase()) {
            "A5" -> A5
            "A4" -> A4
            else -> SIX_BY_NINE
        }
    }
}

/** What goes into the book, before anything knows which page it lands on. */
sealed interface ContentBlock {
    /** Starts a chapter, and always a new page. */
    data class Heading(val chapterId: Long, val text: String, val sourcePage: Int = 0) : ContentBlock

    data class Paragraph(val text: String) : ContentBlock

    data class Image(
        val path: String,
        val widthPx: Int,
        val heightPx: Int,
        val caption: String = "",
    ) : ContentBlock
}

/** A line of text with its final position on a page. */
data class PlacedLine(
    val text: String,
    val xPt: Float,
    /** Baseline distance from the top of the page. */
    val yPt: Float,
    val sizePt: Float,
    val bold: Boolean = false,
)

data class PlacedImage(
    val path: String,
    val xPt: Float,
    /** Distance from the top of the page to the image's top edge. */
    val yPt: Float,
    val widthPt: Float,
    val heightPt: Float,
)

/** A clickable region on a contents page, pointing at the chapter it names. */
data class PlacedLink(
    val chapterId: Long,
    val xPt: Float,
    /** Distance from the top of the page to the top edge of the clickable box. */
    val yPt: Float,
    val widthPt: Float,
    val heightPt: Float,
)

data class LaidOutPage(
    val index: Int,
    val lines: List<PlacedLine> = emptyList(),
    val images: List<PlacedImage> = emptyList(),
    /** Chapters that begin on this page, for bookmarks and the table of contents. */
    val chapterStarts: List<Long> = emptyList(),
    /** Contents rows on this page that should be clickable. */
    val links: List<PlacedLink> = emptyList(),
)

/** One row of the rebuilt index. */
data class TocEntry(
    val chapterId: Long,
    val title: String,
    val outputPage: Int,
    val sourcePage: Int,
)

/**
 * Measures a string's width. Injected so pagination can be unit-tested without a PDF font
 * or an Android canvas in the loop.
 */
fun interface TextWidth {
    fun widthOf(text: String, sizePt: Float, bold: Boolean): Float
}
