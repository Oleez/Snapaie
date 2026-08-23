package com.snapaie.android.data.ingest

import android.graphics.BitmapFactory
import com.snapaie.android.domain.book.ChapterHint
import com.snapaie.android.domain.book.HtmlToText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

/**
 * Reads an EPUB into the same shape [PdfIngestor] produces.
 *
 * No library: an EPUB is a zip of XHTML with a manifest, and the three files that matter
 * (`META-INF/container.xml`, the OPF, and the spine documents) are small enough to scan
 * directly. That avoids taking on `epublib`, which has been unmaintained for a decade.
 *
 * Spine order *is* reading order, and in practice one spine document is one chapter, so
 * chapter boundaries fall out of the format rather than needing the heading heuristics.
 */
class EpubIngestor {

    suspend fun ingest(
        source: File,
        imageDir: File,
        onProgress: suspend (IngestProgress) -> Unit = {},
    ): IngestedDocument = withContext(Dispatchers.IO) {
        imageDir.mkdirs()
        ZipFile(source).use { zip ->
            val opfPath = readContainer(zip) ?: error("This EPUB has no container.xml root file.")
            val opfDir = opfPath.substringBeforeLast('/', "")
            val opf = zip.readText(opfPath) ?: error("This EPUB's package document is unreadable.")

            val manifest = parseManifest(opf)
            val spine = parseSpine(opf).mapNotNull { id -> manifest[id]?.let { id to it } }
            if (spine.isEmpty()) error("This EPUB has an empty spine.")

            val builder = StringBuilder()
            val chapterHints = mutableListOf<ChapterHint>()
            val images = mutableListOf<IngestedImage>()
            var imageOrder = 0

            spine.forEachIndexed { index, (_, href) ->
                coroutineContext.ensureActive()
                val entryPath = resolve(opfDir, href)
                val html = zip.readText(entryPath) ?: return@forEachIndexed
                val parsed = HtmlToText.convert(html)
                if (parsed.text.isBlank()) return@forEachIndexed

                val base = builder.length
                // Every spine document opens a chapter, titled from its first heading.
                chapterHints += ChapterHint(
                    title = parsed.headings.firstOrNull()?.second ?: "Chapter ${chapterHints.size + 1}",
                    startChar = base,
                    page = index + 1,
                )

                parsed.images.forEach { (offset, src) ->
                    val resolved = resolve(entryPath.substringBeforeLast('/', ""), src)
                    val extracted = extractImage(zip, resolved, imageDir, imageOrder)
                    if (extracted != null) {
                        images += extracted.copy(srcChar = base + offset, srcPage = index + 1)
                        imageOrder++
                    }
                }

                builder.append(parsed.text)
                builder.append("\n\n")
                onProgress(IngestProgress(index + 1, spine.size))
            }

            IngestedDocument(
                title = tagText(opf, "dc:title") ?: source.nameWithoutExtension,
                author = tagText(opf, "dc:creator").orEmpty(),
                text = builder.toString(),
                pageCount = spine.size,
                pageStartChars = emptyList(),
                chapterHints = chapterHints,
                images = images,
            )
        }
    }

    private fun readContainer(zip: ZipFile): String? {
        val xml = zip.readText("META-INF/container.xml") ?: return null
        return Regex("""full-path\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(xml)?.groupValues?.get(1)
    }

    /** `id -> href` for every manifest item. */
    private fun parseManifest(opf: String): Map<String, String> =
        Regex("""<item\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(opf).mapNotNull { match ->
            val tag = match.value
            val id = attr(tag, "id") ?: return@mapNotNull null
            val href = attr(tag, "href") ?: return@mapNotNull null
            id to href
        }.toMap()

    /** `idref`s in spine order, skipping anything explicitly marked non-linear. */
    private fun parseSpine(opf: String): List<String> {
        val spine = Regex("""<spine\b[^>]*>(.*?)</spine>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(opf)?.groupValues?.get(1) ?: return emptyList()
        return Regex("""<itemref\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(spine).mapNotNull { match ->
            val tag = match.value
            if (attr(tag, "linear")?.equals("no", ignoreCase = true) == true) return@mapNotNull null
            attr(tag, "idref")
        }.toList()
    }

    private fun extractImage(
        zip: ZipFile,
        path: String,
        imageDir: File,
        order: Int,
    ): IngestedImage? {
        val entry = zip.findEntry(path) ?: return null
        val bytes = runCatching { zip.getInputStream(entry).use { it.readBytes() } }.getOrNull() ?: return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth < MIN_IMAGE_PX || bounds.outHeight < MIN_IMAGE_PX) return null

        val extension = path.substringAfterLast('.', "jpg").lowercase().take(4)
        val target = File(imageDir, "e%04d.%s".format(order, extension))
        return runCatching {
            target.writeBytes(bytes)
            IngestedImage(
                path = target.absolutePath,
                srcPage = 0,
                srcChar = 0,
                widthPx = bounds.outWidth,
                heightPx = bounds.outHeight,
            )
        }.getOrNull()
    }

    private fun tagText(xml: String, tag: String): String? =
        Regex("""<$tag\b[^>]*>(.*?)</$tag>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }

    private fun attr(tag: String, name: String): String? =
        Regex("""\b$name\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(tag)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    /** Resolves an href against a base directory, collapsing `..` and URL-decoding. */
    private fun resolve(baseDir: String, href: String): String {
        val clean = href.substringBefore('#').replace("%20", " ")
        if (clean.startsWith("/")) return clean.trimStart('/')
        val parts = ArrayList<String>()
        if (baseDir.isNotEmpty()) parts += baseDir.split('/').filter { it.isNotEmpty() }
        clean.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts += part
            }
        }
        return parts.joinToString("/")
    }

    private fun ZipFile.readText(path: String): String? {
        val entry = findEntry(path) ?: return null
        return runCatching { getInputStream(entry).bufferedReader().use { it.readText() } }.getOrNull()
    }

    /**
     * Zip lookups are case- and encoding-sensitive, and EPUB hrefs regularly disagree with
     * the archive on both. Exact match first, then a forgiving scan.
     */
    private fun ZipFile.findEntry(path: String): ZipEntry? {
        getEntry(path)?.let { return it }
        val wanted = path.lowercase()
        return entries().asSequence().firstOrNull { it.name.lowercase() == wanted }
            ?: entries().asSequence().firstOrNull { it.name.substringAfterLast('/').lowercase() == wanted.substringAfterLast('/') }
    }

    private companion object {
        const val MIN_IMAGE_PX = 100
    }
}
