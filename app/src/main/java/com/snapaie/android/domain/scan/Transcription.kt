package com.snapaie.android.domain.scan

import com.snapaie.android.domain.condense.BeatContract

/**
 * Turning a model's reply to "read this page" into the text of the page.
 *
 * A model asked to transcribe does not always transcribe. It answers "This appears to be a
 * handwritten note about...", or opens with "Sure, here is the text:", or explains that it
 * cannot make out the writing. Each of those is a sentence *about* the page rather than a
 * sentence *from* it, and letting one through would replace the document with a description
 * of itself — after which nothing downstream could tell, because a description condenses
 * just as happily as a transcription.
 */
object Transcription {

    /** Openings a model uses when it is describing a page instead of reproducing it. */
    private val DESCRIPTION_OPENINGS = listOf(
        "this appears to be", "this image shows", "the image shows", "this is a page",
        "this is an image", "the page contains", "here is the text", "here's the text",
        "sure, here", "certainly, here", "i can see", "i'm unable", "i am unable",
        "i cannot", "i can't", "unfortunately", "the handwriting", "the document shows",
    )

    /** Preambles worth removing rather than rejecting the whole reply for. */
    private val STRIPPABLE_PREFIXES = listOf(
        "here is the text:", "here's the text:", "transcription:", "text:",
    )

    /**
     * The page's text, or blank when the reply was not one.
     *
     * Blank is a real answer here. A page nobody could read should reach the user as "that
     * could not be read", not as the model's opinion of it.
     */
    fun clean(raw: String): String {
        var text = BeatContract.cleanProse(raw).trim()
        if (text.isBlank() || BeatContract.isRuntimeNoise(text)) return ""

        STRIPPABLE_PREFIXES.forEach { prefix ->
            if (text.startsWith(prefix, ignoreCase = true)) {
                text = text.removeRange(0, prefix.length).trim()
            }
        }
        if (text.isBlank()) return ""

        val opening = text.take(90).lowercase()
        if (DESCRIPTION_OPENINGS.any { opening.startsWith(it) || opening.contains(it) }) return ""

        // A page that is nothing but "[unclear]" markers was not read either.
        val withoutMarkers = text.replace("[unclear]", "", ignoreCase = true).trim()
        if (withoutMarkers.length < MIN_USEFUL_CHARS) return ""

        return text
    }

    /** Below this there is no page here, only fragments. */
    private const val MIN_USEFUL_CHARS = 12
}
