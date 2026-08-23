package com.snapaie.android.domain.output

import com.snapaie.android.data.local.BookAssetEntity
import com.snapaie.android.data.local.BookBeatEntity
import com.snapaie.android.data.local.BookChapterEntity

/**
 * Turns stored chapters, beats and images into the block stream the layout engine flows.
 *
 * The interesting part is where the pictures go. Images are anchored to the beat whose
 * text surrounded them in the *source*, which is exactly right for a first-pass book: the
 * beats are still in the same order, so a figure lands beside the passage it belonged to
 * even though the pagination is completely different.
 *
 * A second ladder pass breaks that link, because its beats were cut from the first pass's
 * output rather than from the source. Rather than guess at a character offset that no
 * longer means anything, the chapter's images are spread evenly through the chapter's
 * beats — still in order, still in the right chapter, just no longer sentence-accurate.
 */
object BookContentBuilder {

    fun build(
        chapters: List<BookChapterEntity>,
        beatsByChapter: Map<Long, List<BookBeatEntity>>,
        assetsByBeat: Map<Long, List<BookAssetEntity>>,
        /** Beat ids of pass 1, per chapter, used to find images for a later pass. */
        sourceBeatsByChapter: Map<Long, List<Long>> = emptyMap(),
        includeImages: Boolean = true,
    ): List<ContentBlock> = buildList {
        chapters.sortedBy { it.orderIndex }.forEach { chapter ->
            val beats = beatsByChapter[chapter.id].orEmpty().sortedBy { it.orderIndex }
            if (beats.isEmpty()) return@forEach

            add(
                ContentBlock.Heading(
                    chapterId = chapter.id,
                    text = chapter.title.ifBlank { "Chapter ${chapter.orderIndex + 1}" },
                    sourcePage = chapter.srcPageFrom,
                ),
            )

            val direct = beats.any { assetsByBeat.containsKey(it.id) }
            val spread = if (includeImages && !direct) {
                spreadImages(chapter.id, beats.size, assetsByBeat, sourceBeatsByChapter)
            } else {
                emptyMap()
            }

            beats.forEachIndexed { index, beat ->
                paragraphsOf(beat.outputText).forEach { add(ContentBlock.Paragraph(it)) }
                if (!includeImages) return@forEachIndexed
                val images = assetsByBeat[beat.id] ?: spread[index].orEmpty()
                images.forEach { asset ->
                    add(
                        ContentBlock.Image(
                            path = asset.path,
                            widthPx = asset.widthPx,
                            heightPx = asset.heightPx,
                            caption = asset.captionText,
                        ),
                    )
                }
            }
        }
    }

    private fun spreadImages(
        chapterId: Long,
        beatCount: Int,
        assetsByBeat: Map<Long, List<BookAssetEntity>>,
        sourceBeatsByChapter: Map<Long, List<Long>>,
    ): Map<Int, List<BookAssetEntity>> {
        val images = sourceBeatsByChapter[chapterId].orEmpty()
            .flatMap { assetsByBeat[it].orEmpty() }
            .sortedWith(compareBy({ it.srcPage }, { it.srcChar }, { it.orderInBeat }))
        if (images.isEmpty() || beatCount <= 0) return emptyMap()

        return images.withIndex().groupBy(
            keySelector = { (index, _) -> (index * beatCount) / images.size },
            valueTransform = { (_, asset) -> asset },
        )
    }

    /** Blank-line separated paragraphs, with the model's stray leading bullets removed. */
    fun paragraphsOf(text: String): List<String> =
        text.split(Regex("""\n\s*\n"""))
            .map { it.trim().removePrefix("- ").removePrefix("* ").trim() }
            .filter { it.isNotBlank() }
}
