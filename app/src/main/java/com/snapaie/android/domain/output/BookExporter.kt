package com.snapaie.android.domain.output

import com.snapaie.android.data.book.BookRepository
import com.snapaie.android.data.book.BookStorage
import com.snapaie.android.data.local.BookAssetDao
import com.snapaie.android.data.local.BookDao
import com.snapaie.android.data.local.BookExportDao
import com.snapaie.android.data.local.BookExportEntity
import com.snapaie.android.data.model.BookExportFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class ExportRequest(
    val bookId: Long,
    val format: BookExportFormat,
    val pass: Int,
    val pageSize: String = "6x9",
    val includeImages: Boolean = true,
)

data class ExportResult(
    val file: File,
    val format: BookExportFormat,
    val pageCount: Int,
)

/**
 * Writes a finished book out.
 *
 * All four formats are built from the same block stream, so the PDF, the EPUB and the
 * Markdown are the same book — the same chapters in the same order with the same figures.
 * Only the PDF is paginated, because only the PDF has pages.
 */
class BookExporter(
    private val repository: BookRepository,
    private val storage: BookStorage,
    private val bookDao: BookDao,
    private val assetDao: BookAssetDao,
    private val exportDao: BookExportDao,
    private val pdfWriter: PdfBookWriter = PdfBookWriter(),
    private val epubWriter: EpubBookWriter = EpubBookWriter(),
) {

    suspend fun export(request: ExportRequest): ExportResult = withContext(Dispatchers.IO) {
        val book = bookDao.getBook(request.bookId) ?: error("That book is gone.")
        val blocks = blocksFor(request)
        if (blocks.isEmpty()) error("There is nothing condensed to export yet.")

        val stem = book.title.replace(Regex("""[^A-Za-z0-9 _-]"""), "").trim().ifBlank { "book" }
        val target = File(storage.exportDir(request.bookId), "$stem.${request.format.extension}")

        when (request.format) {
            BookExportFormat.PDF -> {
                val spec = PageSpec.named(request.pageSize)
                // The writer owns the font, so the engine must measure with the same one
                // or the pagination it computes will not match what gets drawn.
                val layout = BookLayoutEngine(spec, pdfWriter.textWidth).layout(blocks)
                pdfWriter.write(layout, spec, book.title, book.author, target)
                record(request, target, layout.pageCount)
            }

            BookExportFormat.EPUB -> {
                epubWriter.write(blocks, book.title, book.author, target)
                record(request, target, 0)
            }

            BookExportFormat.MARKDOWN -> {
                target.writeText(markdown(blocks, book.title, book.author))
                record(request, target, 0)
            }

            BookExportFormat.TEXT -> {
                target.writeText(plainText(blocks, book.title))
                record(request, target, 0)
            }
        }
    }

    private suspend fun blocksFor(request: ExportRequest): List<ContentBlock> {
        val chapters = bookDao.getChapters(request.bookId)
        val beats = bookDao.getBeats(request.bookId, request.pass).filter { it.outputText.isNotBlank() }
        val assets = assetDao.getAssets(request.bookId)
        val assetsByBeat = assets.filter { it.anchorBeatId != null }.groupBy { it.anchorBeatId!! }

        // Images anchor to first-pass beats, so a later pass needs the map back to them.
        val sourceBeatsByChapter = if (request.pass > BookRepository.FIRST_PASS) {
            bookDao.getBeats(request.bookId, BookRepository.FIRST_PASS)
                .groupBy({ it.chapterId }, { it.id })
        } else {
            emptyMap()
        }

        val body = BookContentBuilder.build(
            chapters = chapters,
            beatsByChapter = beats.groupBy { it.chapterId },
            assetsByBeat = assetsByBeat,
            sourceBeatsByChapter = sourceBeatsByChapter,
            includeImages = request.includeImages,
        )
        if (body.isEmpty()) return body

        // Say what this is before the story starts. Handing someone an abridgement without
        // telling them is how a reader ends up thinking a passage is missing from the book.
        val book = bookDao.getBook(request.bookId)
        return FrontMatter.build(
            title = book?.title.orEmpty().ifBlank { "Untitled" },
            author = book?.author.orEmpty(),
            sourceWords = book?.sourceWordCount ?: 0,
            outputWords = beats.sumOf { it.outputWords },
            chapterCount = chapters.count { chapter -> beats.any { it.chapterId == chapter.id } },
        ) + body
    }

    private suspend fun record(request: ExportRequest, file: File, pageCount: Int): ExportResult {
        exportDao.insertExport(
            BookExportEntity(
                bookId = request.bookId,
                format = request.format.name,
                path = file.absolutePath,
                pageCount = pageCount,
                createdAtMillis = System.currentTimeMillis(),
            ),
        )
        return ExportResult(file, request.format, pageCount)
    }

    private fun markdown(blocks: List<ContentBlock>, title: String, author: String): String = buildString {
        appendLine("# $title")
        if (author.isNotBlank()) appendLine("*$author*")
        appendLine()
        blocks.forEach { block ->
            when (block) {
                is ContentBlock.Heading -> {
                    appendLine()
                    appendLine("## ${block.text}")
                    appendLine()
                }
                is ContentBlock.Paragraph -> {
                    appendLine(block.text)
                    appendLine()
                }
                is ContentBlock.Image -> {
                    appendLine("![${block.caption}](${File(block.path).name})")
                    appendLine()
                }
            }
        }
        appendLine("---")
        appendLine("*Condensed on-device with snapaie.*")
    }

    private fun plainText(blocks: List<ContentBlock>, title: String): String = buildString {
        appendLine(title)
        appendLine()
        blocks.forEach { block ->
            when (block) {
                is ContentBlock.Heading -> {
                    appendLine()
                    appendLine(block.text.uppercase())
                    appendLine()
                }
                is ContentBlock.Paragraph -> {
                    appendLine(block.text)
                    appendLine()
                }
                is ContentBlock.Image -> if (block.caption.isNotBlank()) {
                    appendLine("[image: ${block.caption}]")
                    appendLine()
                }
            }
        }
    }
}
