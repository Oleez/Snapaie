package com.snapaie.android.data.book

import android.net.Uri
import com.snapaie.android.data.ingest.EpubIngestor
import com.snapaie.android.data.ingest.IngestProgress
import com.snapaie.android.data.ingest.IngestedDocument
import com.snapaie.android.data.ingest.PdfIngestor
import com.snapaie.android.data.local.BookAssetDao
import com.snapaie.android.data.local.BookAssetEntity
import com.snapaie.android.data.local.BookBeatEntity
import com.snapaie.android.data.local.BookChapterEntity
import com.snapaie.android.data.local.BookDao
import com.snapaie.android.data.local.BookEntity
import com.snapaie.android.data.local.CondenseDao
import com.snapaie.android.data.local.CondenseJobEntity
import com.snapaie.android.data.model.BeatStatus
import com.snapaie.android.data.model.BookAssetKind
import com.snapaie.android.data.model.BookImportState
import com.snapaie.android.data.model.BookSourceKind
import com.snapaie.android.data.model.CondenseJobState
import com.snapaie.android.data.model.CondenseTargetKind
import com.snapaie.android.domain.book.Segmenter
import com.snapaie.android.domain.condense.BudgetGovernor
import com.snapaie.android.domain.condense.CondenseTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Turns a source file into a book the condense pipeline can work through.
 *
 * Import is the only place the whole document is held in memory at once. After it, the
 * flattened text lives on disk and every later stage addresses it by character range, so
 * a 500-page book costs a few hundred kilobytes of process memory rather than tens of
 * megabytes competing with a 2 GB model for RAM.
 */
class BookRepository(
    private val storage: BookStorage,
    private val bookDao: BookDao,
    private val condenseDao: CondenseDao,
    private val assetDao: BookAssetDao,
    private val pdfIngestor: PdfIngestor,
    private val epubIngestor: EpubIngestor,
) {

    fun observeBooks(): Flow<List<BookEntity>> = bookDao.observeBooks()

    fun observeBook(id: Long): Flow<BookEntity?> = bookDao.observeBook(id)

    fun observeChapters(bookId: Long) = bookDao.observeChapters(bookId)

    fun observeBeats(bookId: Long, pass: Int) = bookDao.observeBeats(bookId, pass)

    fun observeProgress(bookId: Long, pass: Int) = bookDao.observeProgress(bookId, pass)

    fun observeLatestJob(bookId: Long) = condenseDao.observeLatestJob(bookId)

    suspend fun readText(bookId: Long): String = withContext(Dispatchers.IO) { storage.readText(bookId) }

    /**
     * Reads a document in and segments it.
     *
     * The row is created *before* the bytes are copied, because the book id names the
     * directory they go into. A failure at any later point leaves the row in FAILED with
     * the reason attached rather than losing the user's file silently.
     */
    suspend fun import(
        uri: Uri,
        kind: BookSourceKind,
        displayName: String,
        onProgress: suspend (IngestProgress) -> Unit = {},
    ): Long = withContext(Dispatchers.IO) {
        val bookId = bookDao.insertBook(
            BookEntity(
                title = displayName.substringBeforeLast('.').ifBlank { "Untitled" },
                sourceKind = kind.name,
                importState = BookImportState.IMPORTING.name,
                createdAtMillis = System.currentTimeMillis(),
            ),
        )

        try {
            val extension = when (kind) {
                BookSourceKind.PDF -> "pdf"
                BookSourceKind.EPUB -> "epub"
                else -> "txt"
            }
            val source = storage.copyIn(bookId, uri, extension)
                ?: error("That file could not be read. Try sharing it again.")

            val document = when (kind) {
                BookSourceKind.EPUB -> epubIngestor.ingest(source, storage.imageDir(bookId), onProgress)
                else -> pdfIngestor.ingest(source, storage.imageDir(bookId), onProgress)
            }
            if (document.text.isBlank()) {
                error("No readable text was found in this file.")
            }

            persist(bookId, document)
            bookId
        } catch (error: Throwable) {
            bookDao.getBook(bookId)?.let { book ->
                bookDao.updateBook(
                    book.copy(
                        importState = BookImportState.FAILED.name,
                        importError = error.message ?: "This file could not be imported.",
                    ),
                )
            }
            throw error
        }
    }

    private suspend fun persist(bookId: Long, document: IngestedDocument) {
        storage.writeText(bookId, document.text)

        val chapters = Segmenter.detectChapters(document.text, document.chapterHints)
        val chapterIds = bookDao.insertChapters(
            chapters.map { chapter ->
                BookChapterEntity(
                    bookId = bookId,
                    orderIndex = chapter.orderIndex,
                    title = chapter.title,
                    srcStartChar = chapter.startChar,
                    srcEndChar = chapter.endChar,
                    srcPageFrom = chapter.page.takeIf { it > 0 } ?: document.pageAt(chapter.startChar),
                )
            },
        )

        val beats = Segmenter.segmentBeats(document.text, chapters)
        val beatIds = bookDao.insertBeats(
            beats.map { beat ->
                BookBeatEntity(
                    bookId = bookId,
                    chapterId = chapterIds.getOrElse(beat.chapterIndex) { chapterIds.first() },
                    pass = FIRST_PASS,
                    orderIndex = beat.orderIndex,
                    srcStartChar = beat.startChar,
                    srcEndChar = beat.endChar,
                    srcWords = beat.words,
                    srcPageFrom = document.pageAt(beat.startChar),
                    srcPageTo = document.pageAt(beat.endChar),
                    status = BeatStatus.PENDING.name,
                )
            },
        )

        persistAssets(bookId, document, beats.map { it.startChar to it.endChar }, beatIds)

        bookDao.getBook(bookId)?.let { book ->
            bookDao.updateBook(
                book.copy(
                    title = document.title.ifBlank { book.title },
                    author = document.author,
                    sourcePath = storage.textFile(bookId).absolutePath,
                    sourcePageCount = document.pageCount,
                    sourceWordCount = Segmenter.countWords(document.text),
                    sourceCharCount = document.text.length,
                    coverPath = document.images.firstOrNull()?.path,
                    importState = BookImportState.READY.name,
                    importError = null,
                ),
            )
        }
    }

    private suspend fun persistAssets(
        bookId: Long,
        document: IngestedDocument,
        beatRanges: List<Pair<Int, Int>>,
        beatIds: List<Long>,
    ) {
        if (document.images.isEmpty()) return
        assetDao.insertAssets(
            document.images.map { image ->
                BookAssetEntity(
                    bookId = bookId,
                    kind = BookAssetKind.IMAGE.name,
                    path = image.path,
                    srcPage = image.srcPage,
                    srcChar = image.srcChar,
                    widthPx = image.widthPx,
                    heightPx = image.heightPx,
                    captionText = image.caption,
                )
            },
        )
        beatRanges.forEachIndexed { index, (start, end) ->
            val beatId = beatIds.getOrNull(index) ?: return@forEachIndexed
            assetDao.anchorAssetsToBeat(bookId, beatId, start, end)
        }
        // An image past the last beat's range would otherwise never be drawn at all.
        beatIds.lastOrNull()?.let { assetDao.anchorRemainingAssets(bookId, it) }
    }

    // region Jobs

    /**
     * Creates (or reuses) the condense job for a book.
     *
     * Reuse matters: tapping start twice, or coming back to a paused run, must continue
     * the existing beats rather than wiping them and beginning a 4-hour job again.
     */
    suspend fun startJob(
        bookId: Long,
        targetKind: CondenseTargetKind,
        targetValue: Int,
        chargingOnly: Boolean,
    ): CondenseJobEntity? = withContext(Dispatchers.IO) {
        val book = bookDao.getBook(bookId) ?: return@withContext null
        val existing = condenseDao.latestJob(bookId)
        if (existing != null && existing.state != CondenseJobState.COMPLETED.name) {
            val resumed = existing.copy(state = CondenseJobState.QUEUED.name, errorMessage = null)
            condenseDao.updateJob(resumed)
            return@withContext resumed
        }

        val targetWords = when (targetKind) {
            CondenseTargetKind.PAGES -> CondenseTarget.wordsForPages(targetValue)
            CondenseTargetKind.PERCENT -> CondenseTarget.wordsForPercent(book.sourceWordCount, targetValue)
        }.coerceAtMost(book.sourceWordCount.coerceAtLeast(1))

        // A very aggressive target goes via an intermediate pass; pass 1 always aims for
        // 30% so its output is a readable book in its own right rather than scrap.
        val passOneWords = if (CondenseTarget.needsLadder(targetWords, book.sourceWordCount)) {
            (book.sourceWordCount * CondenseTarget.FIRST_PASS_RATIO).toInt()
        } else {
            targetWords
        }

        val id = condenseDao.insertJob(
            CondenseJobEntity(
                bookId = bookId,
                targetKind = targetKind.name,
                targetValue = targetValue,
                pass = FIRST_PASS,
                state = CondenseJobState.QUEUED.name,
                targetWords = passOneWords,
                chargingOnly = chargingOnly,
                startedAtMillis = System.currentTimeMillis(),
            ),
        )
        condenseDao.getJob(id)
    }

    suspend fun setJobState(jobId: Long, state: CondenseJobState) = withContext(Dispatchers.IO) {
        condenseDao.setJobState(jobId, state.name)
    }

    suspend fun latestJob(bookId: Long): CondenseJobEntity? =
        withContext(Dispatchers.IO) { condenseDao.latestJob(bookId) }

    suspend fun updateJob(job: CondenseJobEntity) = withContext(Dispatchers.IO) {
        condenseDao.updateJob(job)
    }

    /** Rebuilds the governor from stored totals, which is the whole of resume. */
    suspend fun governorFor(job: CondenseJobEntity, book: BookEntity): BudgetGovernor =
        withContext(Dispatchers.IO) {
            val progress = bookDao.getProgress(job.bookId, job.pass)
            BudgetGovernor(
                totalTargetWords = job.targetWords,
                totalSourceWords = if (job.pass == FIRST_PASS) book.sourceWordCount else progress.total,
                producedWords = progress.outputWords,
                consumedSourceWords = progress.srcWordsDone,
            )
        }

    suspend fun delete(bookId: Long) = withContext(Dispatchers.IO) {
        bookDao.deleteBook(bookId)
        storage.delete(bookId)
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        bookDao.deleteAllBooks()
        storage.deleteAll()
    }

    // endregion

    companion object {
        const val FIRST_PASS = 1
    }
}
