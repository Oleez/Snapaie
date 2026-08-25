package com.snapaie.android.domain.condense

/** What the model gave back for one beat, split into its two halves. */
data class BeatResponse(
    val prose: String,
    val ledgerPatch: StoryLedger?,
)

/** Why a beat's output was not acceptable. */
enum class BeatRejection {
    NONE,
    EMPTY,
    TOO_SHORT,
    META_FRAMING,
    LOST_NAMES,
}

/**
 * The contract between the pipeline and the model for a single beat.
 *
 * Splitting, validation and prompt assembly all live here as pure functions so the rules
 * that decide "is this a faithful retelling or a summary?" are unit-testable without a
 * 2 GB model in the loop.
 */
object BeatContract {

    const val LEDGER_DELIMITER = "<<<LEDGER>>>"

    /**
     * Phrases that mean the model stopped retelling and started describing.
     *
     * This is the single most common failure: asked to shorten a passage, a small model
     * slips into "In this chapter, the author introduces…" — which is a summary, reads
     * nothing like the book, and cannot be stitched to the beats on either side.
     */
    private val META_MARKERS = listOf(
        "in this chapter", "in this passage", "in this section", "this chapter",
        "this passage", "this excerpt", "the author", "the narrator describes",
        "the text describes", "the story begins", "to summarize", "to summarise",
        "in summary", "summary:", "the passage describes", "here is a condensed",
        "here's a condensed", "condensed version", "the following is",
    )

    private val PROPER_NOUN = Regex("""\b[A-Z][a-z]{2,}\b""")
    private const val QUOTE_CHARS = "\"')]’”"

    /**
     * Splits a raw response into prose and ledger patch.
     *
     * Everything before the delimiter is prose; everything after is the ledger. A missing
     * or unparseable ledger is not an error — the caller carries the previous one forward,
     * because losing continuity state is far cheaper than discarding good prose.
     */
    fun split(raw: String): BeatResponse {
        val trimmed = raw.trim()
        val at = trimmed.indexOf(LEDGER_DELIMITER)
        if (at < 0) return BeatResponse(cleanProse(trimmed), null)
        return BeatResponse(
            prose = cleanProse(trimmed.substring(0, at)),
            ledgerPatch = StoryLedger.parse(trimmed.substring(at + LEDGER_DELIMITER.length)),
        )
    }

    /**
     * Strips the scaffolding small models wrap around an answer — code fences, a restated
     * instruction, a leading label — without touching the prose itself.
     */
    /**
     * Runtime failure text that has been observed arriving as *content* rather than as a
     * thrown error — glued straight onto the front of a real retelling with no separator,
     * and shipped to the reader as part of the page.
     *
     * The channel that did that is fixed, but a reply is worth checking anyway: this is the
     * last point before text reaches someone, and no page ever legitimately says these.
     */
    private val RUNTIME_NOISE = listOf(
        "stream error",
        "Status Code:",
        "should not be null",
        "TryLoadingVisionExecutor",
        "LiteRT",
    )

    /** True when the reply is runtime noise rather than a retelling. */
    fun isRuntimeNoise(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        return RUNTIME_NOISE.count { trimmed.contains(it, ignoreCase = true) } >= 2
    }

    fun cleanProse(raw: String): String {
        var text = stripRuntimeNoise(raw.trim())
        if (text.startsWith("```")) {
            text = text.removePrefix("```").substringAfter('\n', "").substringBeforeLast("```").trim()
        }
        listOf("CONDENSED:", "OUTPUT:", "PROSE:", "Condensed passage:", "Passage:").forEach { label ->
            if (text.startsWith(label, ignoreCase = true)) {
                text = text.removeRange(0, label.length).trim()
            }
        }
        return text.trim()
    }

    /**
     * Removes a leading runtime error, keeping whatever real text followed it.
     *
     * The observed shape is the message running straight into the prose without so much as
     * a space, so the cut is made at the end of the last marker's sentence rather than at a
     * line break, which there is not one of.
     */
    private fun stripRuntimeNoise(text: String): String {
        val last = RUNTIME_NOISE
            .mapNotNull { marker -> text.lastIndexOf(marker, ignoreCase = true).takeIf { it >= 0 } }
            .maxOrNull() ?: return text

        var cut = text.indexOf('.', last)
        while (cut >= 0 && cut + 1 < text.length && !text[cut + 1].isLetter() && text[cut + 1] != ' ') {
            cut = text.indexOf('.', cut + 1)
        }
        if (cut < 0) return ""
        return text.substring(cut + 1).trim()
    }

    /**
     * Decides whether a beat's prose is a faithful retelling.
     *
     * Deliberately cheap and conservative — it runs after every beat of a multi-hour job,
     * and a false rejection costs a whole re-generation. It only catches the failures that
     * are unambiguous from the text alone.
     */
    fun evaluate(prose: String, sourceText: String, budgetWords: Int): BeatRejection {
        if (prose.isBlank()) return BeatRejection.EMPTY

        val words = prose.split(Regex("""\s+""")).count { it.isNotBlank() }
        if (budgetWords > 0 && words < budgetWords * MIN_LENGTH_RATIO) return BeatRejection.TOO_SHORT

        if (startsWithMetaFraming(prose)) return BeatRejection.META_FRAMING

        // Proper nouns are the load-bearing detail in a story. If the source had names and
        // the retelling kept none of them, the model generalised the scene away.
        val sourceNames = PROPER_NOUN.findAll(sourceText).map { it.value }.toSet() - COMMON_CAPITALISED
        if (sourceNames.size >= MIN_NAMES_TO_CHECK) {
            val kept = sourceNames.count { prose.contains(it) }
            if (kept == 0) return BeatRejection.LOST_NAMES
        }

        return BeatRejection.NONE
    }

    /**
     * Meta-framing only counts at the start of a sentence near the top of the passage.
     * "the author" appearing halfway through a beat is very likely the story talking about
     * an actual author character, not the model narrating its own task.
     */
    private fun startsWithMetaFraming(prose: String): Boolean {
        val head = prose.take(META_SCAN_CHARS).lowercase()
        return META_MARKERS.any { marker ->
            val at = head.indexOf(marker)
            at >= 0 && beginsSentence(head, at)
        }
    }

    /** True when [at] is the start of the passage or the start of a sentence within it. */
    private fun beginsSentence(text: String, at: Int): Boolean {
        var cursor = at - 1
        while (cursor >= 0 && (text[cursor].isWhitespace() || text[cursor] in QUOTE_CHARS)) cursor--
        return cursor < 0 || text[cursor] in ".!?:"
    }

    /** Words that are capitalised for grammar, not because they name anything. */
    private val COMMON_CAPITALISED = setOf(
        "The", "This", "That", "There", "Then", "They", "Their", "These", "Those",
        "But", "And", "For", "Not", "Now", "One", "Two", "Was", "Were", "When", "What",
        "With", "While", "After", "Before", "Because", "Her", "His", "She", "Him",
        "You", "Your", "Its", "Our", "Had", "Have", "Has", "Did", "Does", "All", "Any",
        "Some", "Such", "Only", "Even", "Just", "Still", "Never", "Nothing", "Something",
        "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday",
        "January", "February", "March", "April", "June", "July", "August",
        "September", "October", "November", "December", "Chapter",
    )

    private const val MIN_LENGTH_RATIO = 0.25f
    private const val MIN_NAMES_TO_CHECK = 3
    private const val META_SCAN_CHARS = 200
}
