package com.snapaie.android.domain.output

/**
 * The pages before the story starts.
 *
 * An abridged edition needs to say so. Without a title page and a note explaining what was
 * cut, a reader has no way to tell whether they are holding the book or something derived
 * from it — and someone who later notices a missing passage has been misled rather than
 * served. The note is also where the promise is made explicit: the sentences that remain
 * are the author's own, in the author's order.
 */
object FrontMatter {

    /** Average adult reading speed for prose, in words per minute. */
    private const val SLOW_WPM = 220
    private const val FAST_WPM = 300

    fun build(
        title: String,
        author: String,
        sourceWords: Int,
        outputWords: Int,
        chapterCount: Int,
    ): List<ContentBlock> = buildList {
        add(ContentBlock.Heading(chapterId = TITLE_PAGE_ID, text = title))
        add(ContentBlock.Paragraph("An Abridged Reader's Edition"))
        if (author.isNotBlank()) add(ContentBlock.Paragraph(author))
        add(ContentBlock.Paragraph(readingTimeLine(outputWords)))

        add(ContentBlock.Heading(chapterId = NOTE_PAGE_ID, text = "A note on this edition"))
        add(ContentBlock.Paragraph(explanation(sourceWords, outputWords, chapterCount)))
        add(
            ContentBlock.Paragraph(
                "Every sentence here is the original author's, exactly as written, in the " +
                    "order they wrote it. Nothing has been reworded and nothing has been " +
                    "added. The editing is entirely a matter of what was left out.",
            ),
        )
    }

    fun readingTimeLine(words: Int): String {
        if (words <= 0) return ""
        val fast = (words / FAST_WPM).coerceAtLeast(1)
        val slow = (words / SLOW_WPM).coerceAtLeast(fast + 1)
        return "Approx. ${"%,d".format(words)} words · roughly $fast–$slow minutes"
    }

    private fun explanation(sourceWords: Int, outputWords: Int, chapterCount: Int): String {
        val percent = if (sourceWords > 0) (outputWords * 100 / sourceWords).coerceIn(1, 99) else 0
        return buildString {
            append("This is an abridged reader's edition, not a summary. ")
            if (chapterCount > 0) {
                append("Its $chapterCount ")
                append(if (chapterCount == 1) "section keeps " else "sections keep ")
                append("the original structure and the order of events. ")
            }
            if (sourceWords > 0) {
                append("It runs to about $percent% of the original's length. ")
            }
            append(
                "What was cut is repetition, description that restates what is already " +
                    "established, and digressions that introduce nothing new — so the story " +
                    "moves faster without becoming a synopsis.",
            )
        }
    }

    /** Sentinel chapter ids, so the front matter never collides with a real chapter. */
    const val TITLE_PAGE_ID = -1L
    const val NOTE_PAGE_ID = -2L
}
