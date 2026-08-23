package com.snapaie.android.domain.output

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes the condensed book as a reflowable EPUB.
 *
 * Worth having alongside the PDF because they answer different needs: a PDF is a fixed
 * artefact with the page count the user asked for, an EPUB reflows to whatever device it
 * lands on and inherits that reader's font size and night mode. The rebuilt index is a
 * real EPUB 3 nav document, so it works the same way the PDF bookmarks do.
 */
class EpubBookWriter {

    suspend fun write(
        blocks: List<ContentBlock>,
        title: String,
        author: String,
        target: File,
    ): File = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val chapters = splitChapters(blocks)
        val images = blocks.filterIsInstance<ContentBlock.Image>()
            .mapNotNull { File(it.path).takeIf(File::isFile) }
            .distinctBy { it.absolutePath }
            .associateBy { it.name }

        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            // The mimetype entry must be first and stored uncompressed; readers check it
            // by byte offset before they will open the archive at all.
            writeStored(zip, "mimetype", "application/epub+zip".toByteArray())

            write(zip, "META-INF/container.xml", CONTAINER_XML)
            write(zip, "OEBPS/content.opf", opf(title, author, chapters, images.keys))
            write(zip, "OEBPS/nav.xhtml", nav(title, chapters))
            write(zip, "OEBPS/style.css", STYLE_CSS)

            chapters.forEachIndexed { index, chapter ->
                write(zip, "OEBPS/chapter%03d.xhtml".format(index), chapterXhtml(chapter))
            }
            images.forEach { (name, file) ->
                zip.putNextEntry(ZipEntry("OEBPS/images/$name"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        target
    }

    private data class Chapter(val title: String, val blocks: List<ContentBlock>)

    private fun splitChapters(blocks: List<ContentBlock>): List<Chapter> {
        val chapters = mutableListOf<Chapter>()
        var title = "Beginning"
        var current = mutableListOf<ContentBlock>()

        blocks.forEach { block ->
            if (block is ContentBlock.Heading) {
                if (current.isNotEmpty()) chapters += Chapter(title, current.toList())
                title = block.text
                current = mutableListOf()
            } else {
                current += block
            }
        }
        if (current.isNotEmpty()) chapters += Chapter(title, current.toList())
        return chapters
    }

    private fun chapterXhtml(chapter: Chapter): String = buildString {
        appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
        appendLine("""<html xmlns="http://www.w3.org/1999/xhtml"><head>""")
        appendLine("""<meta charset="utf-8"/><title>${escape(chapter.title)}</title>""")
        appendLine("""<link rel="stylesheet" type="text/css" href="style.css"/></head><body>""")
        appendLine("<h1>${escape(chapter.title)}</h1>")
        chapter.blocks.forEach { block ->
            when (block) {
                is ContentBlock.Paragraph -> appendLine("<p>${escape(block.text)}</p>")
                is ContentBlock.Image -> {
                    appendLine("""<figure><img src="images/${escape(File(block.path).name)}" alt=""/>""")
                    if (block.caption.isNotBlank()) {
                        appendLine("<figcaption>${escape(block.caption)}</figcaption>")
                    }
                    appendLine("</figure>")
                }
                is ContentBlock.Heading -> appendLine("<h2>${escape(block.text)}</h2>")
            }
        }
        appendLine("</body></html>")
    }

    private fun opf(
        title: String,
        author: String,
        chapters: List<Chapter>,
        imageNames: Set<String>,
    ): String = buildString {
        appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
        appendLine("""<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">""")
        appendLine("""<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">""")
        appendLine("<dc:identifier id=\"bookid\">urn:snapaie:${System.currentTimeMillis()}</dc:identifier>")
        appendLine("<dc:title>${escape(title)}</dc:title>")
        appendLine("<dc:language>en</dc:language>")
        if (author.isNotBlank()) appendLine("<dc:creator>${escape(author)}</dc:creator>")
        appendLine("""<meta property="dcterms:modified">${timestamp()}</meta>""")
        appendLine("</metadata><manifest>")
        appendLine("""<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""")
        appendLine("""<item id="css" href="style.css" media-type="text/css"/>""")
        chapters.indices.forEach { index ->
            val name = "chapter%03d.xhtml".format(index)
            appendLine("""<item id="c$index" href="$name" media-type="application/xhtml+xml"/>""")
        }
        imageNames.forEachIndexed { index, name ->
            appendLine("""<item id="img$index" href="images/${escape(name)}" media-type="${mediaType(name)}"/>""")
        }
        appendLine("</manifest><spine>")
        chapters.indices.forEach { appendLine("""<itemref idref="c$it"/>""") }
        appendLine("</spine></package>")
    }

    private fun nav(title: String, chapters: List<Chapter>): String = buildString {
        appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
        appendLine("""<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">""")
        appendLine("""<head><meta charset="utf-8"/><title>${escape(title)}</title></head><body>""")
        appendLine("""<nav epub:type="toc" id="toc"><h1>Contents</h1><ol>""")
        chapters.forEachIndexed { index, chapter ->
            appendLine("""<li><a href="chapter%03d.xhtml">${escape(chapter.title)}</a></li>""".format(index))
        }
        appendLine("</ol></nav></body></html>")
    }

    private fun mediaType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }

    private fun timestamp(): String {
        val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        format.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return format.format(java.util.Date())
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun write(zip: ZipOutputStream, path: String, body: String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(body.toByteArray())
        zip.closeEntry()
    }

    private fun writeStored(zip: ZipOutputStream, path: String, bytes: ByteArray) {
        val entry = ZipEntry(path).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            crc = CRC32().apply { update(bytes) }.value
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private companion object {
        val CONTAINER_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
        """.trimIndent()

        val STYLE_CSS = """
            body { line-height: 1.5; margin: 0 5%; }
            h1 { margin-top: 2em; page-break-before: always; }
            p { text-indent: 1.2em; margin: 0 0 0.2em; }
            p:first-of-type, h1 + p { text-indent: 0; }
            figure { margin: 1.5em 0; text-align: center; page-break-inside: avoid; }
            figure img { max-width: 100%; }
            figcaption { font-size: 0.85em; opacity: 0.75; }
        """.trimIndent()
    }
}
