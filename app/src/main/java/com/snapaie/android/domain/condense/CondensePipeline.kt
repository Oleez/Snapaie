package com.snapaie.android.domain.condense

import com.snapaie.android.data.ai.ModelSessionManager
import com.snapaie.android.data.book.BookRepository
import com.snapaie.android.data.book.BookStorage
import com.snapaie.android.data.local.BookBeatEntity
import com.snapaie.android.data.local.BookDao
import com.snapaie.android.data.model.BeatStatus
import com.snapaie.android.data.model.CondenseJobState
import com.snapaie.android.domain.book.Segmenter
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Emitted after each beat so the UI can show real progress and a real estimate. */
data class CondenseProgress(
    val bookId: Long,
    val pass: Int,
    val beatsDone: Int,
    val beatsTotal: Int,
    val chapterTitle: String,
    val producedWords: Int,
    val usedFallback: Boolean,
)

/** Why a run stopped. */
sealed interface CondenseOutcome {
    data class Completed(val pass: Int, val producedWords: Int) : CondenseOutcome
    data class LadderPassReady(val nextPass: Int) : CondenseOutcome
    data object NothingToDo : CondenseOutcome
    data class Failed(val message: String) : CondenseOutcome
}

/**
 * Works through a book's beats, in order, until none are left.
 *
 * There is no "restart" path and no separate resume path, because the database already
 * holds the position: the loop asks for the next beat that is not finished, and that is
 * true whether this is minute one or a restart after the phone rebooted overnight. The
 * only state that has to be recovered is the story ledger and the tail of the previous
 * passage, both of which were stored on the last beat that completed.
 */
class CondensePipeline(
    private val repository: BookRepository,
    private val storage: BookStorage,
    private val bookDao: BookDao,
    private val condenser: PassageCondenser,
    private val sessionManager: ModelSessionManager,
) {

    suspend fun run(
        bookId: Long,
        onProgress: suspend (CondenseProgress) -> Unit = {},
    ): CondenseOutcome {
        val book = bookDao.getBook(bookId) ?: return CondenseOutcome.Failed("That book is gone.")
        val job = repository.latestJob(bookId) ?: return CondenseOutcome.NothingToDo
        // Asks the condenser, not the engine. A cloud run needs no local model, and
        // gating on one meant a book could not be condensed by the very thing that
        // condenses books quickly.
        if (!condenser.isReady()) {
            return CondenseOutcome.Failed("Turn on offline AI, or connect Cloud Read, to shorten a book.")
        }

        val pass = job.pass
        val text = passText(bookId, pass)
        if (text.isBlank()) return CondenseOutcome.Failed("This book has no readable text.")

        val chapterTitles = bookDao.getChapters(bookId).associate { it.id to it.title }
        val governor = repository.governorFor(job, book)

        // Recover continuity from the last finished beat; empty on a fresh run.
        val resumeFrom = bookDao.lastCompletedBeat(bookId, pass)
        var ledger = resumeFrom?.ledgerJson?.let { StoryLedger.decode(it) } ?: StoryLedger.EMPTY
        var previousTail = resumeFrom?.outputText.orEmpty()

        repository.setJobState(job.id, CondenseJobState.RUNNING)

        // Keeps the engine resident: this loop runs for hours, backgrounded by definition,
        // and reloading 2 GB of weights between beats would dominate the runtime.
        sessionManager.acquireKeepAlive().use {
            while (true) {
                coroutineContext.ensureActive()
                val beat = bookDao.nextPendingBeat(bookId, pass) ?: break

                bookDao.updateBeat(beat.copy(status = BeatStatus.RUNNING.name))

                val source = text.substring(
                    beat.srcStartChar.coerceIn(0, text.length),
                    beat.srcEndChar.coerceIn(0, text.length),
                )
                val budget = governor.budgetFor(beat.srcWords)
                // What is coming next, so a cloud condenser can put several passages in one
                // request instead of one round trip each. A local condenser ignores it.
                condenser.setLookahead { limit ->
                    bookDao.getBeats(bookId, pass)
                        .asSequence()
                        .filter { it.status == BeatStatus.PENDING.name && it.id != beat.id }
                        .sortedBy { it.orderIndex }
                        .take(limit)
                        .map {
                            text.substring(
                                it.srcStartChar.coerceIn(0, text.length),
                                it.srcEndChar.coerceIn(0, text.length),
                            )
                        }
                        .toList()
                }
                val result = condenser.condense(
                    sourceText = source,
                    ledger = ledger,
                    previousTail = previousTail,
                    budgetWords = budget,
                )

                bookDao.updateBeat(
                    beat.copy(
                        status = if (result.usedFallback) BeatStatus.FALLBACK.name else BeatStatus.CONDENSED.name,
                        attempts = result.attempts,
                        targetWords = budget,
                        outputText = result.prose,
                        outputWords = result.words,
                        ledgerJson = StoryLedger.encode(result.ledger),
                        errorMessage = result.lastRejection.takeIf { it != BeatRejection.NONE }?.name,
                    ),
                )

                governor.record(result.words, beat.srcWords)
                ledger = result.ledger
                previousTail = result.prose

                val progress = bookDao.getProgress(bookId, pass)
                onProgress(
                    CondenseProgress(
                        bookId = bookId,
                        pass = pass,
                        beatsDone = progress.done,
                        beatsTotal = progress.total,
                        chapterTitle = chapterTitles[beat.chapterId].orEmpty(),
                        producedWords = progress.outputWords,
                        usedFallback = result.usedFallback,
                    ),
                )
            }
        }

        return finish(bookId, job.id, pass, book.sourceWordCount)
    }

    private suspend fun finish(
        bookId: Long,
        jobId: Long,
        pass: Int,
        sourceWords: Int,
    ): CondenseOutcome {
        val progress = bookDao.getProgress(bookId, pass)
        if (!progress.isComplete) {
            return CondenseOutcome.Failed("The run stopped before every passage was written.")
        }

        val job = repository.latestJob(bookId) ?: return CondenseOutcome.NothingToDo
        val finalTargetWords = finalTargetWords(job.targetKind, job.targetValue, sourceWords)

        // Was this the intermediate rung of the ladder? If so, the next pass condenses
        // what we just produced. Pass 1's output stays on disk either way — it is a
        // readable book at ~30%, not scrap.
        val needsAnotherPass = pass == BookRepository.FIRST_PASS &&
            CondenseTarget.needsLadder(finalTargetWords, sourceWords)

        if (!needsAnotherPass) {
            repository.updateJob(
                job.copy(
                    state = CondenseJobState.COMPLETED.name,
                    producedWords = progress.outputWords,
                    finishedAtMillis = System.currentTimeMillis(),
                ),
            )
            return CondenseOutcome.Completed(pass, progress.outputWords)
        }

        val nextPass = pass + 1
        prepareLadderPass(bookId, pass, nextPass)
        repository.updateJob(
            job.copy(
                pass = nextPass,
                state = CondenseJobState.QUEUED.name,
                targetWords = finalTargetWords,
                producedWords = 0,
            ),
        )
        return CondenseOutcome.LadderPassReady(nextPass)
    }

    private fun finalTargetWords(targetKind: String, targetValue: Int, sourceWords: Int): Int =
        when (com.snapaie.android.data.model.CondenseTargetKind.fromStored(targetKind)) {
            com.snapaie.android.data.model.CondenseTargetKind.PAGES ->
                CondenseTarget.wordsForPages(targetValue)
            com.snapaie.android.data.model.CondenseTargetKind.PERCENT ->
                CondenseTarget.wordsForPercent(sourceWords, targetValue)
        }

    /**
     * Builds the next pass's source out of the previous pass's output.
     *
     * Done chapter by chapter so chapter boundaries survive the rung exactly — the reader
     * still navigates by the book's own chapters, and a beat still never straddles one.
     */
    private suspend fun prepareLadderPass(bookId: Long, fromPass: Int, toPass: Int) {
        val previous = bookDao.getBeats(bookId, fromPass)
        if (previous.isEmpty()) return

        val builder = StringBuilder()
        val newBeats = mutableListOf<BookBeatEntity>()
        var order = 0

        previous.groupBy { it.chapterId }.forEach { (chapterId, beats) ->
            val chapterStart = builder.length
            beats.sortedBy { it.orderIndex }.forEach { beat ->
                builder.append(beat.outputText.trim())
                builder.append("\n\n")
            }
            val chapterEnd = builder.length
            val chapterText = builder.substring(chapterStart, chapterEnd)

            val chapter = com.snapaie.android.domain.book.SourceChapter(
                orderIndex = 0,
                title = "",
                startChar = 0,
                endChar = chapterText.length,
            )
            Segmenter.segmentBeats(chapterText, listOf(chapter)).forEach { beat ->
                newBeats += BookBeatEntity(
                    bookId = bookId,
                    chapterId = chapterId,
                    pass = toPass,
                    orderIndex = order++,
                    srcStartChar = chapterStart + beat.startChar,
                    srcEndChar = chapterStart + beat.endChar,
                    srcWords = beat.words,
                    status = BeatStatus.PENDING.name,
                )
            }
        }

        storage.passTextFile(bookId, toPass).writeText(builder.toString())
        bookDao.deleteBeats(bookId, toPass)
        bookDao.insertBeats(newBeats)
    }

    private fun passText(bookId: Long, pass: Int): String =
        if (pass <= BookRepository.FIRST_PASS) {
            storage.readText(bookId)
        } else {
            storage.passTextFile(bookId, pass).let { if (it.isFile) it.readText() else "" }
        }
}
