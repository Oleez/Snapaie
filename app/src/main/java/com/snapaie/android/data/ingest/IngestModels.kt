package com.snapaie.android.data.ingest

import com.snapaie.android.domain.book.ChapterHint

/** An image lifted out of a source document, already written to app storage. */
data class IngestedImage(
    val path: String,
    val srcPage: Int,
    /** Character offset in [IngestedDocument.text] this image sits nearest to. */
    val srcChar: Int,
    val widthPx: Int,
    val heightPx: Int,
    val caption: String = "",
)

/**
 * The result of reading a source file: one flat text stream plus everything needed to
 * map back into it.
 *
 * A single string rather than a list of pages is deliberate. Chapters, beats and image
 * anchors are all character ranges over this one buffer, which is what lets the coverage
 * invariant in [com.snapaie.android.domain.book.Segmenter] be stated at all.
 */
data class IngestedDocument(
    val title: String,
    val author: String,
    val text: String,
    val pageCount: Int,
    /** `pageStartChars[i]` is where page `i` begins in [text]. Empty for page-less sources. */
    val pageStartChars: List<Int>,
    val chapterHints: List<ChapterHint>,
    val images: List<IngestedImage>,
    /** Pages that had no text layer and had to be read with OCR. */
    val ocrPageCount: Int = 0,
) {
    /** 1-based page number containing [charOffset], or 0 when the source has no pages. */
    fun pageAt(charOffset: Int): Int {
        if (pageStartChars.isEmpty()) return 0
        val index = pageStartChars.binarySearch { it.compareTo(charOffset) }
        return if (index >= 0) index + 1 else (-index - 2).coerceAtLeast(0) + 1
    }
}

/** Progress while a source file is being read. */
data class IngestProgress(
    val page: Int,
    val totalPages: Int,
    val usedOcr: Boolean = false,
)
