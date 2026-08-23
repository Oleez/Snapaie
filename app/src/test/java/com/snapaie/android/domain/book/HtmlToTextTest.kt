package com.snapaie.android.domain.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlToTextTest {

    @Test
    fun `block tags become paragraph breaks`() {
        val result = HtmlToText.convert("<p>First line.</p><p>Second line.</p>")
        assertEquals("First line.\n\nSecond line.", result.text.trim())
    }

    @Test
    fun `entities are decoded`() {
        val result = HtmlToText.convert("<p>Tom &amp; Jerry &mdash; &#65;&#x42; &quot;hi&quot;</p>")
        assertEquals("Tom & Jerry \u2014 AB \"hi\"", result.text.trim())
    }

    @Test
    fun `a bare ampersand is left alone rather than eating the text after it`() {
        val result = HtmlToText.convert("<p>Fish & chips cost 5 &lt; 10</p>")
        assertEquals("Fish & chips cost 5 < 10", result.text.trim())
    }

    @Test
    fun `image offsets index into the decoded text not the raw html`() {
        // The entity before the image is the trap: decoding after the fact would leave
        // every recorded offset four characters too far to the right.
        val html = "<p>Tom &amp; Jerry</p><img src=\"pics/one.png\"/><p>After.</p>"
        val result = HtmlToText.convert(html)
        val (offset, src) = result.images.single()
        assertEquals("pics/one.png", src)
        assertTrue("offset $offset outside text of ${result.text.length}", offset <= result.text.length)
        assertEquals("Tom & Jerry", result.text.substring(0, offset).trim())
    }

    @Test
    fun `headings are captured with their offsets`() {
        val result = HtmlToText.convert("<h1>Chapter One</h1><p>Body text.</p><h2>A Scene</h2><p>More.</p>")
        assertEquals(listOf("Chapter One", "A Scene"), result.headings.map { it.second })
        result.headings.forEach { (offset, title) ->
            assertTrue(result.text.substring(offset).trimStart().startsWith(title))
        }
    }

    @Test
    fun `script and style content is dropped`() {
        val result = HtmlToText.convert(
            "<head><title>x</title></head><style>p { color: red; }</style>" +
                "<script>var a = 1 < 2;</script><p>Only this.</p>",
        )
        assertEquals("Only this.", result.text.trim())
    }

    @Test
    fun `malformed markup degrades instead of throwing`() {
        listOf(
            "<p>Unclosed paragraph",
            "<p>Broken <b>bold</p>",
            "<p>Stray < angle bracket</p>",
            "<img src=unquoted.png><p>Text</p>",
            "<p>Tail with an open tag <span",
            "",
        ).forEach { html ->
            val result = HtmlToText.convert(html)
            assertTrue("threw or lost everything for: $html", result.text.length >= 0)
        }
        assertEquals("unquoted.png", HtmlToText.convert("<img src=unquoted.png><p>Text</p>").images.single().second)
    }

    @Test
    fun `svg image href is picked up alongside img src`() {
        val result = HtmlToText.convert("""<svg><image xlink:href="cover.jpeg"/></svg><p>Body</p>""")
        assertEquals("cover.jpeg", result.images.single().second)
    }

    @Test
    fun `an attribute is not matched inside another attribute name`() {
        // "data-src" must not satisfy a lookup for "src".
        val result = HtmlToText.convert("""<img data-src="wrong.png" src="right.png"/>""")
        assertEquals("right.png", result.images.single().second)
    }

    @Test
    fun `a less-than inside a script does not swallow the rest of the document`() {
        // The regression that made this worth a test: scanning script content tag-by-tag
        // reads "1 < 2;</script" as one tag, eats the real closing tag with it, and loses
        // every remaining chapter.
        val result = HtmlToText.convert("<script>if (1 < 2) { x(); }</script><p>Survives.</p>")
        assertEquals("Survives.", result.text.trim())
    }

    @Test
    fun `a stray angle bracket in prose stays in the text`() {
        val result = HtmlToText.convert("<p>5 < 10 and a > b</p>")
        assertEquals("5 < 10 and a > b", result.text.trim())
    }
}
