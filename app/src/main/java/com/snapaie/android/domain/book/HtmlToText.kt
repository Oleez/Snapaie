package com.snapaie.android.domain.book

/** Flattened XHTML: plain text plus where the headings and images sat inside it. */
data class HtmlText(
    val text: String,
    /** `charOffset to heading text`, in document order. */
    val headings: List<Pair<Int, String>>,
    /** `charOffset to src attribute`, in document order. */
    val images: List<Pair<Int, String>>,
)

/**
 * Turns EPUB chapter XHTML into plain text.
 *
 * Hand-rolled rather than XmlPullParser on purpose: EPUB content in the wild is regularly
 * not well-formed — unclosed `<br>`, stray `&`, mismatched tags from twenty years of
 * conversion tools — and a strict parser throws on the first one, losing a whole chapter.
 * A tolerant scanner degrades to slightly odd whitespace instead, which the segmenter does
 * not care about.
 *
 * Entities are decoded *as the text is built*, never afterwards: heading and image offsets
 * index into the final string, and a later decode pass would silently shift every one of
 * them by however many entities preceded it.
 */
object HtmlToText {

    private val BLOCK_TAGS = setOf(
        "p", "div", "br", "li", "tr", "blockquote", "section", "article",
        "h1", "h2", "h3", "h4", "h5", "h6", "hr", "figure", "figcaption", "table", "pre",
    )
    private val HEADING_TAGS = setOf("h1", "h2", "h3")
    private val SKIP_TAGS = setOf("script", "style", "head")

    private val NAMED_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "mdash" to "\u2014", "ndash" to "\u2013", "hellip" to "\u2026",
        "lsquo" to "\u2018", "rsquo" to "\u2019", "ldquo" to "\u201C", "rdquo" to "\u201D",
        "eacute" to "\u00E9", "egrave" to "\u00E8", "agrave" to "\u00E0", "ccedil" to "\u00E7",
        "uuml" to "\u00FC", "ouml" to "\u00F6", "auml" to "\u00E4", "szlig" to "\u00DF",
        "copy" to "\u00A9", "reg" to "\u00AE", "trade" to "\u2122", "deg" to "\u00B0",
    )

    fun convert(html: String): HtmlText {
        val out = StringBuilder(html.length / 2)
        val headings = mutableListOf<Pair<Int, String>>()
        val images = mutableListOf<Pair<Int, String>>()

        var index = 0
        var headingTag: String? = null
        var headingStart = 0

        while (index < html.length) {
            val ch = html[index]

            if (ch == '&') {
                val end = html.indexOf(';', index + 1)
                val decoded = if (end in (index + 1)..(index + MAX_ENTITY_LENGTH)) {
                    decodeEntity(html.substring(index + 1, end))
                } else {
                    null
                }
                if (decoded != null) {
                    out.append(decoded)
                    index = end + 1
                } else {
                    out.append(ch)
                    index++
                }
                continue
            }

            val next = html.getOrNull(index + 1)
            val startsTag = ch == '<' && next != null && (next.isLetter() || next in "/!?")
            if (!startsTag) {
                out.append(ch)
                index++
                continue
            }

            val close = html.indexOf('>', index + 1)
            if (close < 0) break
            val raw = html.substring(index + 1, close)
            index = close + 1

            // Comments, CDATA and processing instructions carry no text.
            if (raw.startsWith("!") || raw.startsWith("?")) continue

            val isClosing = raw.startsWith("/")
            val name = raw.removePrefix("/")
                .takeWhile { !it.isWhitespace() && it != '/' }
                .lowercase()

            if (!isClosing && name in SKIP_TAGS) {
                // script and style are raw-text elements: their content is not markup, so
                // scanning it tag-by-tag mis-reads things like "if (a < b)" as a tag and
                // swallows the real closing tag along with the rest of the document.
                index = skipPast(html, index, name)
                continue
            }

            if (!isClosing) {
                when (name) {
                    "img" -> attribute(raw, "src")?.let { images += out.length to it }
                    "image" -> (attribute(raw, "xlink:href") ?: attribute(raw, "href"))
                        ?.let { images += out.length to it }
                }
            }

            if (name in BLOCK_TAGS) {
                appendBreak(out)
                if (name in HEADING_TAGS) {
                    if (!isClosing) {
                        headingTag = name
                        headingStart = out.length
                    } else if (headingTag == name) {
                        val title = out.substring(headingStart).trim()
                        if (title.isNotEmpty()) headings += headingStart to title
                        headingTag = null
                    }
                }
            }
        }

        return HtmlText(text = out.toString(), headings = headings, images = images)
    }

    /** Index just past `</name>`, or the end of the document if it never closes. */
    private fun skipPast(html: String, from: Int, name: String): Int {
        val marker = "</" + name
        val at = html.indexOf(marker, from, ignoreCase = true)
        if (at < 0) return html.length
        val close = html.indexOf('>', at)
        return if (close < 0) html.length else close + 1
    }

    /**
     * Collapses runs of whitespace into a paragraph break. Two newlines is what the
     * segmenter treats as a paragraph boundary, so block tags map straight onto it.
     */
    private fun appendBreak(out: StringBuilder) {
        while (out.isNotEmpty() && out.last().isWhitespace()) out.setLength(out.length - 1)
        if (out.isNotEmpty()) out.append("\n\n")
    }

    private fun decodeEntity(body: String): String? {
        if (body.isEmpty() || body.length > MAX_ENTITY_LENGTH) return null
        if (body.startsWith("#")) {
            val code = if (body.startsWith("#x", ignoreCase = true)) {
                body.drop(2).toIntOrNull(16)
            } else {
                body.drop(1).toIntOrNull()
            } ?: return null
            if (code !in 1..0x10FFFF) return null
            return runCatching { String(Character.toChars(code)) }.getOrNull()
        }
        return NAMED_ENTITIES[body.lowercase()]
    }

    /** Reads `name="value"` / `name='value'` / bare `name=value` out of a raw tag body. */
    private fun attribute(rawTag: String, name: String): String? {
        val lower = rawTag.lowercase()
        var search = 0
        while (true) {
            val at = lower.indexOf(name.lowercase(), search)
            if (at < 0) return null
            search = at + name.length
            // Must be a whole attribute name, not the tail of another one.
            val before = rawTag.getOrNull(at - 1)
            if (before != null && !before.isWhitespace()) continue

            var cursor = search
            while (cursor < rawTag.length && rawTag[cursor].isWhitespace()) cursor++
            if (cursor >= rawTag.length || rawTag[cursor] != '=') continue
            cursor++
            while (cursor < rawTag.length && rawTag[cursor].isWhitespace()) cursor++
            if (cursor >= rawTag.length) return null

            val quote = rawTag[cursor]
            return if (quote == '"' || quote == '\'') {
                val end = rawTag.indexOf(quote, cursor + 1)
                if (end < 0) null else rawTag.substring(cursor + 1, end)
            } else {
                rawTag.substring(cursor).takeWhile { !it.isWhitespace() && it != '/' }
                    .takeIf { it.isNotEmpty() }
            }
        }
    }

    private const val MAX_ENTITY_LENGTH = 10
}
