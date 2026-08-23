package com.snapaie.android.domain.output

import kotlin.math.min

/** A finished layout: pages, plus where every chapter landed. */
data class BookLayout(
    val pages: List<LaidOutPage>,
    val toc: List<TocEntry>,
    /** How many leading pages the table of contents occupies. */
    val tocPageCount: Int,
) {
    val pageCount: Int get() = pages.size
}

/**
 * Flows blocks into pages and rebuilds the index against the result.
 *
 * The index is the part that needs care. Its length depends on the page numbers it lists,
 * and those page numbers depend on how long the index is — a genuine fixed point, not a
 * single pass. Laying the body out once, reading the chapter pages, then writing a table
 * of contents in front of it shifts every one of those numbers by however many pages the
 * contents took. So it iterates until the count stops moving, which in practice is one or
 * two rounds.
 *
 * Pure apart from an injected [TextWidth], so pagination is testable without a PDF font.
 */
class BookLayoutEngine(
    private val spec: PageSpec,
    private val measure: TextWidth,
) {

    fun layout(blocks: List<ContentBlock>, tocTitle: String = "Contents"): BookLayout {
        var tocPages = 1
        var result = flow(blocks, leadingPages = tocPages)

        repeat(MAX_TOC_ITERATIONS) {
            val entries = tocEntries(blocks, result)
            val needed = measureTocPages(entries, tocTitle)
            if (needed == tocPages) {
                return BookLayout(
                    pages = renderToc(entries, tocTitle, needed) + result,
                    toc = entries,
                    tocPageCount = needed,
                )
            }
            tocPages = needed
            result = flow(blocks, leadingPages = tocPages)
        }

        val entries = tocEntries(blocks, result)
        return BookLayout(
            pages = renderToc(entries, tocTitle, tocPages) + result,
            toc = entries,
            tocPageCount = tocPages,
        )
    }

    /** Body pages, numbered as though [leadingPages] contents pages sit in front. */
    private fun flow(blocks: List<ContentBlock>, leadingPages: Int): List<LaidOutPage> {
        val pages = mutableListOf<LaidOutPage>()
        var lines = mutableListOf<PlacedLine>()
        var images = mutableListOf<PlacedImage>()
        var chapterStarts = mutableListOf<Long>()
        var cursor = 0f
        var pageIndex = leadingPages

        fun flush() {
            if (lines.isEmpty() && images.isEmpty()) return
            pages += LaidOutPage(pageIndex, lines.toList(), images.toList(), chapterStarts.toList())
            pageIndex++
            lines = mutableListOf()
            images = mutableListOf()
            chapterStarts = mutableListOf()
            cursor = 0f
        }

        blocks.forEach { block ->
            when (block) {
                is ContentBlock.Heading -> {
                    // Chapters always open a fresh page, the way a printed book does.
                    flush()
                    chapterStarts += block.chapterId
                    cursor = spec.headingLeadingPt
                    wrap(block.text, spec.textWidthPt, spec.headingSizePt, bold = true).forEach { line ->
                        lines += PlacedLine(line, spec.marginPt, spec.marginPt + cursor, spec.headingSizePt, true)
                        cursor += spec.headingLeadingPt
                    }
                    cursor += spec.paragraphGapPt * 2
                }

                is ContentBlock.Paragraph -> {
                    wrap(block.text, spec.textWidthPt, spec.bodySizePt, bold = false).forEach { line ->
                        if (cursor + spec.bodyLeadingPt > spec.textHeightPt) flush()
                        cursor += spec.bodyLeadingPt
                        lines += PlacedLine(line, spec.marginPt, spec.marginPt + cursor, spec.bodySizePt, false)
                    }
                    cursor += spec.paragraphGapPt
                }

                is ContentBlock.Image -> {
                    val (width, height) = fitImage(block)
                    val captionLines = if (block.caption.isBlank()) {
                        emptyList()
                    } else {
                        wrap(block.caption, spec.textWidthPt, spec.captionSizePt, bold = false)
                    }
                    val captionHeight = captionLines.size * spec.captionSizePt * spec.lineHeightRatio
                    val needed = height + captionHeight + spec.paragraphGapPt * 2

                    // An image that will not fit moves whole to the next page rather than
                    // being clipped or shrunk to nothing.
                    if (cursor + needed > spec.textHeightPt && (lines.isNotEmpty() || images.isNotEmpty())) {
                        flush()
                    }
                    cursor += spec.paragraphGapPt
                    images += PlacedImage(
                        path = block.path,
                        xPt = spec.marginPt + (spec.textWidthPt - width) / 2f,
                        yPt = spec.marginPt + cursor,
                        widthPt = width,
                        heightPt = height,
                    )
                    cursor += height
                    captionLines.forEach { line ->
                        cursor += spec.captionSizePt * spec.lineHeightRatio
                        lines += PlacedLine(line, spec.marginPt, spec.marginPt + cursor, spec.captionSizePt, false)
                    }
                    cursor += spec.paragraphGapPt
                }
            }
        }
        flush()
        return pages
    }

    private fun fitImage(block: ContentBlock.Image): Pair<Float, Float> {
        val sourceWidth = block.widthPx.coerceAtLeast(1).toFloat()
        val sourceHeight = block.heightPx.coerceAtLeast(1).toFloat()
        val maxHeight = spec.textHeightPt * MAX_IMAGE_HEIGHT_RATIO
        val scale = min(spec.textWidthPt / sourceWidth, maxHeight / sourceHeight)
        return sourceWidth * scale to sourceHeight * scale
    }

    private fun tocEntries(blocks: List<ContentBlock>, body: List<LaidOutPage>): List<TocEntry> {
        val sourcePages = blocks.filterIsInstance<ContentBlock.Heading>()
            .associate { it.chapterId to it.sourcePage }
        val titles = blocks.filterIsInstance<ContentBlock.Heading>()
            .associate { it.chapterId to it.text }

        return body.flatMap { page ->
            page.chapterStarts.map { chapterId ->
                TocEntry(
                    chapterId = chapterId,
                    title = titles[chapterId].orEmpty(),
                    // Page numbers the reader sees are 1-based and include the contents.
                    outputPage = page.index + 1,
                    sourcePage = sourcePages[chapterId] ?: 0,
                )
            }
        }
    }

    private fun measureTocPages(entries: List<TocEntry>, tocTitle: String): Int {
        if (entries.isEmpty()) return 1
        var cursor = spec.headingLeadingPt + spec.paragraphGapPt * 2
        var pages = 1
        wrap(tocTitle, spec.textWidthPt, spec.headingSizePt, bold = true).forEach { _ ->
            cursor += spec.headingLeadingPt
        }
        entries.forEach { _ ->
            if (cursor + spec.bodyLeadingPt > spec.textHeightPt) {
                pages++
                cursor = 0f
            }
            cursor += spec.bodyLeadingPt
        }
        return pages
    }

    /**
     * The contents pages themselves. Each row carries the new page number and, quietly,
     * the page it was on in the original — which is what makes the condensed edition
     * checkable against the book it came from.
     */
    private fun renderToc(entries: List<TocEntry>, tocTitle: String, pageCount: Int): List<LaidOutPage> {
        if (entries.isEmpty()) return List(pageCount) { LaidOutPage(it) }

        val pages = mutableListOf<LaidOutPage>()
        var lines = mutableListOf<PlacedLine>()
        var links = mutableListOf<PlacedLink>()
        var cursor = spec.headingLeadingPt
        var pageIndex = 0

        wrap(tocTitle, spec.textWidthPt, spec.headingSizePt, bold = true).forEach { line ->
            lines += PlacedLine(line, spec.marginPt, spec.marginPt + cursor, spec.headingSizePt, true)
            cursor += spec.headingLeadingPt
        }
        cursor += spec.paragraphGapPt * 2

        entries.forEach { entry ->
            if (cursor + spec.bodyLeadingPt > spec.textHeightPt) {
                pages += LaidOutPage(pageIndex, lines.toList(), links = links.toList())
                pageIndex++
                lines = mutableListOf()
                links = mutableListOf()
                cursor = 0f
            }
            cursor += spec.bodyLeadingPt
            val number = entry.outputPage.toString()
            val numberWidth = measure.widthOf(number, spec.bodySizePt, false)
            val title = truncateToWidth(
                entry.title,
                spec.textWidthPt - numberWidth - TOC_GUTTER_PT,
                spec.bodySizePt,
            )
            lines += PlacedLine(title, spec.marginPt, spec.marginPt + cursor, spec.bodySizePt, false)
            lines += PlacedLine(
                number,
                spec.marginPt + spec.textWidthPt - numberWidth,
                spec.marginPt + cursor,
                spec.bodySizePt,
                false,
            )
            // The clickable box covers the whole row, title through page number, so the
            // reader can tap anywhere along it rather than exactly on the words.
            links += PlacedLink(
                chapterId = entry.chapterId,
                xPt = spec.marginPt,
                yPt = spec.marginPt + cursor - spec.bodySizePt,
                widthPt = spec.textWidthPt,
                heightPt = spec.bodyLeadingPt,
            )
        }
        pages += LaidOutPage(pageIndex, lines.toList(), links = links.toList())

        // Pad if the measured estimate ran long, so body page numbers stay correct.
        while (pages.size < pageCount) pages += LaidOutPage(pages.size)
        return pages
    }

    /** Greedy line breaking on whitespace; an unbreakable word is hard-split rather than lost. */
    fun wrap(text: String, maxWidthPt: Float, sizePt: Float, bold: Boolean): List<String> {
        val normalised = text.replace(Regex("""\s+"""), " ").trim()
        if (normalised.isEmpty()) return emptyList()

        val lines = mutableListOf<String>()
        val current = StringBuilder()

        normalised.split(' ').forEach { word ->
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (measure.widthOf(candidate, sizePt, bold) <= maxWidthPt) {
                current.setLength(0)
                current.append(candidate)
                return@forEach
            }
            if (current.isNotEmpty()) {
                lines += current.toString()
                current.setLength(0)
            }
            if (measure.widthOf(word, sizePt, bold) <= maxWidthPt) {
                current.append(word)
            } else {
                // A URL or a run of no-break text: split it rather than overflow the page.
                var rest = word
                while (rest.isNotEmpty() && measure.widthOf(rest, sizePt, bold) > maxWidthPt) {
                    var take = rest.length
                    while (take > 1 && measure.widthOf(rest.take(take), sizePt, bold) > maxWidthPt) take--
                    lines += rest.take(take)
                    rest = rest.drop(take)
                }
                current.append(rest)
            }
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines
    }

    private fun truncateToWidth(text: String, maxWidthPt: Float, sizePt: Float): String {
        if (measure.widthOf(text, sizePt, false) <= maxWidthPt) return text
        var take = text.length
        while (take > 1 && measure.widthOf(text.take(take) + "…", sizePt, false) > maxWidthPt) take--
        return text.take(take) + "…"
    }

    private companion object {
        const val MAX_TOC_ITERATIONS = 4
        const val MAX_IMAGE_HEIGHT_RATIO = 0.55f
        const val TOC_GUTTER_PT = 12f
    }
}
