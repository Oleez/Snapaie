package com.snapaie.android.domain.book

/**
 * A chapter boundary supplied by the source itself (a PDF outline entry, an EPUB nav
 * item). Trusted over the heading heuristics when present.
 */
data class ChapterHint(val title: String, val startChar: Int, val page: Int = 0)

/** A chapter as a half-open character range over the extracted text. */
data class SourceChapter(
    val orderIndex: Int,
    val title: String,
    val startChar: Int,
    val endChar: Int,
    val page: Int = 0,
) {
    val length: Int get() = endChar - startChar
}

/** One unit of condensation work, always inside exactly one chapter. */
data class SourceBeat(
    val chapterIndex: Int,
    val orderIndex: Int,
    val startChar: Int,
    val endChar: Int,
    val words: Int,
)

/**
 * Splits a book into chapters and then into beats.
 *
 * The single invariant everything else depends on: **beats tile the text exactly**. Beat
 * 0 starts at 0, each beat starts where the previous one ended, and the last ends at
 * `text.length`. No gaps, no overlaps, no paragraph split across a boundary. That is what
 * turns "the AI didn't skip anything" from a promise into a property the pipeline can
 * check with a query.
 *
 * Pure Kotlin and free of Android types so the invariant is unit-testable.
 */
object Segmenter {

    /**
     * Words of source per beat. Roughly 1,300 tokens, which leaves room for the story
     * ledger, the previous beat's tail and the generated output inside the few-thousand
     * token context LiteRT-LM actually runs a 2B model at — the 128K figure in the model
     * card describes the architecture, not the runtime configuration.
     */
    const val DEFAULT_BEAT_WORDS = 900

    /** Below this a trailing fragment is merged back rather than left as its own beat. */
    private const val MIN_TAIL_WORDS = 120

    private val STRONG_HEADING = Regex(
        """^\s*(chapter|part|book|section|prologue|epilogue|interlude|appendix|volume)\b.{0,60}$""",
        RegexOption.IGNORE_CASE,
    )
    private val NUMERAL_HEADING = Regex("""^\s*(\d{1,3}|[IVXLCDM]{1,7})\s*[.)]?\s*$""")
    private val SCENE_BREAK = Regex("""^\s*([*#~•\-—_]\s*){3,}$|^\s*⁂\s*$""")
    private val WORD = Regex("""\S+""")

    fun countWords(text: String): Int = WORD.findAll(text).count()

    // region Chapters

    /**
     * Chapters covering `[0, text.length)` with no gaps.
     *
     * [hints] from a real outline win outright. Without them the heading heuristics run,
     * and if those find fewer than two headings the whole text is treated as one chapter —
     * a wrong split is worse than no split, because it fragments continuity for nothing.
     */
    fun detectChapters(text: String, hints: List<ChapterHint> = emptyList()): List<SourceChapter> {
        if (text.isEmpty()) return emptyList()

        val boundaries = if (hints.isNotEmpty()) {
            hints.asSequence()
                .filter { it.startChar in 0..text.length }
                .sortedBy { it.startChar }
                .distinctBy { it.startChar }
                .toList()
        } else {
            detectHeadings(text)
        }

        if (boundaries.isEmpty()) {
            return listOf(SourceChapter(0, "Full text", 0, text.length))
        }

        // Text before the first boundary is front matter; it is kept, never dropped.
        val withFront = if (boundaries.first().startChar > 0) {
            listOf(ChapterHint("Front matter", 0)) + boundaries
        } else {
            boundaries
        }

        return withFront.mapIndexed { index, hint ->
            val end = withFront.getOrNull(index + 1)?.startChar ?: text.length
            hint to end
        }.filter { (hint, end) -> end > hint.startChar }
            .mapIndexed { index, (hint, end) ->
                SourceChapter(
                    orderIndex = index,
                    title = hint.title.ifBlank { "Chapter ${index + 1}" },
                    startChar = hint.startChar,
                    endChar = end,
                    page = hint.page,
                )
            }
    }

    /**
     * Heading detection over an isolated-short-line signal.
     *
     * Two tiers: keyword or numeral headings are trusted on their own; a bare Title Case
     * or ALL CAPS line is only trusted when no keyword headings exist anywhere, because in
     * OCR'd text those fire constantly on running heads and figure captions.
     */
    private fun detectHeadings(text: String): List<ChapterHint> {
        val lines = lineRanges(text)
        val strong = mutableListOf<ChapterHint>()
        val weak = mutableListOf<ChapterHint>()

        lines.forEachIndexed { index, range ->
            val raw = text.substring(range.first, range.second)
            val line = raw.trim()
            if (line.isEmpty() || line.length > 80) return@forEachIndexed

            val blankBefore = index == 0 || text.substring(lines[index - 1].first, lines[index - 1].second).isBlank()
            val blankAfter = index == lines.lastIndex ||
                text.substring(lines[index + 1].first, lines[index + 1].second).isBlank()
            if (!blankBefore || !blankAfter) return@forEachIndexed

            when {
                STRONG_HEADING.matches(line) || NUMERAL_HEADING.matches(line) ->
                    strong += ChapterHint(line, range.first)
                looksLikeTitle(line) ->
                    weak += ChapterHint(line, range.first)
            }
        }

        val chosen = if (strong.size >= 2) strong else if (weak.size >= 2) weak else emptyList()
        return chosen
    }

    private fun looksLikeTitle(line: String): Boolean {
        if (line.last() in ".,;:!?") return false
        val words = line.split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (words.isEmpty() || words.size > 10) return false
        val allCaps = line.any { it.isLetter() } && line.filter { it.isLetter() }.all { it.isUpperCase() }
        val titleCase = words.count { it.first().isUpperCase() } >= (words.size + 1) / 2
        return allCaps || titleCase
    }

    /** Half-open `[start, end)` ranges of every line, newline excluded. */
    private fun lineRanges(text: String): List<Pair<Int, Int>> {
        val ranges = mutableListOf<Pair<Int, Int>>()
        var start = 0
        text.forEachIndexed { index, ch ->
            if (ch == '\n') {
                ranges += start to index
                start = index + 1
            }
        }
        ranges += start to text.length
        return ranges
    }

    // endregion

    // region Beats

    /**
     * Beats covering every chapter exactly.
     *
     * Paragraphs are the atom — a beat boundary never lands mid-paragraph, because the
     * model needs whole scenes to retell them faithfully. A scene break (`* * *` and
     * friends) is a preferred boundary: cutting there costs nothing in continuity.
     */
    fun segmentBeats(
        text: String,
        chapters: List<SourceChapter>,
        targetWords: Int = DEFAULT_BEAT_WORDS,
    ): List<SourceBeat> {
        val budget = targetWords.coerceAtLeast(120)
        val beats = mutableListOf<SourceBeat>()
        var order = 0

        chapters.forEach { chapter ->
            val blocks = paragraphBlocks(text, chapter.startChar, chapter.endChar)
            if (blocks.isEmpty()) {
                // Whitespace-only or scene-break-only chapter. It still has to be covered,
                // or the tiling would have a hole in it.
                beats += SourceBeat(
                    chapterIndex = chapter.orderIndex,
                    orderIndex = order++,
                    startChar = chapter.startChar,
                    endChar = chapter.endChar,
                    words = countWords(text.substring(chapter.startChar, chapter.endChar)),
                )
                return@forEach
            }

            var startChar = chapter.startChar
            var carriedWords = 0
            var pendingEnd = chapter.startChar

            blocks.forEachIndexed { index, block ->
                carriedWords += block.words
                // Extend to the end of this block; the gap after it (blank lines) belongs
                // to this beat too, so consecutive beats stay perfectly adjacent.
                pendingEnd = blocks.getOrNull(index + 1)?.start ?: chapter.endChar

                val remainingWords = blocks.drop(index + 1).sumOf { it.words }
                val hitBudget = carriedWords >= budget
                val sceneBreakNext = blocks.getOrNull(index + 1)?.afterSceneBreak == true
                val nearBudget = carriedWords >= budget * 3 / 4

                val shouldCut = when {
                    // Never strand a tiny fragment as its own beat.
                    remainingWords in 1 until MIN_TAIL_WORDS -> false
                    hitBudget -> true
                    nearBudget && sceneBreakNext -> true
                    else -> false
                }

                if (shouldCut && remainingWords > 0) {
                    beats += SourceBeat(
                        chapterIndex = chapter.orderIndex,
                        orderIndex = order++,
                        startChar = startChar,
                        endChar = pendingEnd,
                        words = countWords(text.substring(startChar, pendingEnd)),
                    )
                    startChar = pendingEnd
                    carriedWords = 0
                }
            }

            // Whatever is left closes the chapter, so the tiling reaches chapter.endChar.
            if (startChar < chapter.endChar) {
                beats += SourceBeat(
                    chapterIndex = chapter.orderIndex,
                    orderIndex = order++,
                    startChar = startChar,
                    endChar = chapter.endChar,
                    words = countWords(text.substring(startChar, chapter.endChar)),
                )
            }
        }

        return beats
    }

    private data class Block(val start: Int, val end: Int, val words: Int, val afterSceneBreak: Boolean)

    /**
     * Non-blank paragraph blocks in `[from, to)`. `start` is where the block's text
     * begins; the whitespace between blocks is deliberately left unattributed here and
     * absorbed by the preceding beat in [segmentBeats].
     */
    private fun paragraphBlocks(text: String, from: Int, to: Int): List<Block> {
        val blocks = mutableListOf<Block>()
        var index = from
        var sceneBreakPending = false

        while (index < to) {
            while (index < to && text[index].isWhitespace()) index++
            if (index >= to) break

            val start = index
            // A block runs until a blank line (two newlines with only whitespace between).
            var end = to
            var cursor = index
            while (cursor < to) {
                if (text[cursor] == '\n') {
                    var lookahead = cursor + 1
                    while (lookahead < to && text[lookahead] != '\n' && text[lookahead].isWhitespace()) lookahead++
                    if (lookahead < to && text[lookahead] == '\n') {
                        end = cursor
                        break
                    }
                }
                cursor++
            }
            if (cursor >= to) end = to

            val body = text.substring(start, end)
            if (SCENE_BREAK.matches(body.trim())) {
                // The marker itself carries no story; it just biases the next boundary.
                sceneBreakPending = true
            } else {
                blocks += Block(start, end, countWords(body), sceneBreakPending)
                sceneBreakPending = false
            }
            index = end + 1
        }
        return blocks
    }

    // endregion
}
