package com.snapaie.android.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Aggregate progress for one ladder pass over one book. */
data class BeatProgress(
    val total: Int = 0,
    val done: Int = 0,
    val outputWords: Int = 0,
    val srcWordsDone: Int = 0,
) {
    val remaining: Int get() = (total - done).coerceAtLeast(0)
    val fraction: Float get() = if (total <= 0) 0f else done.toFloat() / total
    val isComplete: Boolean get() = total > 0 && done >= total
}

@Dao
interface BookDao {

    // region Books

    @Query("SELECT * FROM books ORDER BY createdAtMillis DESC")
    fun observeBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    fun observeBook(id: Long): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getBook(id: Long): BookEntity?

    @Insert
    suspend fun insertBook(entity: BookEntity): Long

    @Update
    suspend fun updateBook(entity: BookEntity)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteBook(id: Long)

    @Query("DELETE FROM books")
    suspend fun deleteAllBooks()

    // endregion

    // region Chapters

    @Query("SELECT * FROM book_chapters WHERE bookId = :bookId ORDER BY orderIndex ASC")
    fun observeChapters(bookId: Long): Flow<List<BookChapterEntity>>

    @Query("SELECT * FROM book_chapters WHERE bookId = :bookId ORDER BY orderIndex ASC")
    suspend fun getChapters(bookId: Long): List<BookChapterEntity>

    @Insert
    suspend fun insertChapters(entities: List<BookChapterEntity>): List<Long>

    @Query("UPDATE book_chapters SET outputStartPage = :page WHERE id = :chapterId")
    suspend fun setChapterOutputPage(chapterId: Long, page: Int)

    // endregion

    // region Beats

    @Query("SELECT * FROM book_beats WHERE bookId = :bookId AND pass = :pass ORDER BY orderIndex ASC")
    fun observeBeats(bookId: Long, pass: Int): Flow<List<BookBeatEntity>>

    @Query("SELECT * FROM book_beats WHERE bookId = :bookId AND pass = :pass ORDER BY orderIndex ASC")
    suspend fun getBeats(bookId: Long, pass: Int): List<BookBeatEntity>

    @Query("SELECT * FROM book_beats WHERE id = :id")
    suspend fun getBeat(id: Long): BookBeatEntity?

    /**
     * The next beat to work on. Ordering by index means chapters finish in order, so the
     * reader can start at chapter 1 while the tail of the book is still being processed.
     * RUNNING is included so a beat interrupted by process death is picked back up.
     */
    @Query(
        """
        SELECT * FROM book_beats
        WHERE bookId = :bookId AND pass = :pass AND status IN ('PENDING', 'RUNNING')
        ORDER BY orderIndex ASC LIMIT 1
        """,
    )
    suspend fun nextPendingBeat(bookId: Long, pass: Int): BookBeatEntity?

    @Insert
    suspend fun insertBeats(entities: List<BookBeatEntity>): List<Long>

    @Update
    suspend fun updateBeat(entity: BookBeatEntity)

    @Query("DELETE FROM book_beats WHERE bookId = :bookId AND pass = :pass")
    suspend fun deleteBeats(bookId: Long, pass: Int)

    /**
     * Coverage and progress in one row. `done` counts CONDENSED and FALLBACK alike: both
     * produced text, and the distinction only matters for quality reporting.
     */
    @Query(
        """
        SELECT
            COUNT(*) AS total,
            COALESCE(SUM(CASE WHEN status IN ('CONDENSED', 'FALLBACK') THEN 1 ELSE 0 END), 0) AS done,
            COALESCE(SUM(outputWords), 0) AS outputWords,
            COALESCE(SUM(CASE WHEN status IN ('CONDENSED', 'FALLBACK') THEN srcWords ELSE 0 END), 0) AS srcWordsDone
        FROM book_beats WHERE bookId = :bookId AND pass = :pass
        """,
    )
    fun observeProgress(bookId: Long, pass: Int): Flow<BeatProgress>

    @Query(
        """
        SELECT
            COUNT(*) AS total,
            COALESCE(SUM(CASE WHEN status IN ('CONDENSED', 'FALLBACK') THEN 1 ELSE 0 END), 0) AS done,
            COALESCE(SUM(outputWords), 0) AS outputWords,
            COALESCE(SUM(CASE WHEN status IN ('CONDENSED', 'FALLBACK') THEN srcWords ELSE 0 END), 0) AS srcWordsDone
        FROM book_beats WHERE bookId = :bookId AND pass = :pass
        """,
    )
    suspend fun getProgress(bookId: Long, pass: Int): BeatProgress

    /** Beats that had to fall back to extractive text, so the reader can offer a re-run. */
    @Query("SELECT COUNT(*) FROM book_beats WHERE bookId = :bookId AND pass = :pass AND status = 'FALLBACK'")
    suspend fun countFallbackBeats(bookId: Long, pass: Int): Int

    // endregion
}

@Dao
interface CondenseDao {

    @Query("SELECT * FROM condense_jobs WHERE bookId = :bookId ORDER BY pass DESC, id DESC LIMIT 1")
    fun observeLatestJob(bookId: Long): Flow<CondenseJobEntity?>

    @Query("SELECT * FROM condense_jobs WHERE bookId = :bookId ORDER BY pass DESC, id DESC LIMIT 1")
    suspend fun latestJob(bookId: Long): CondenseJobEntity?

    @Query("SELECT * FROM condense_jobs WHERE state IN ('QUEUED', 'RUNNING', 'PAUSED')")
    fun observeActiveJobs(): Flow<List<CondenseJobEntity>>

    @Query("SELECT * FROM condense_jobs WHERE id = :id")
    suspend fun getJob(id: Long): CondenseJobEntity?

    @Insert
    suspend fun insertJob(entity: CondenseJobEntity): Long

    @Update
    suspend fun updateJob(entity: CondenseJobEntity)

    @Query("UPDATE condense_jobs SET state = :state WHERE id = :id")
    suspend fun setJobState(id: Long, state: String)
}

@Dao
interface BookAssetDao {

    @Query("SELECT * FROM book_assets WHERE bookId = :bookId ORDER BY srcChar ASC, orderInBeat ASC")
    suspend fun getAssets(bookId: Long): List<BookAssetEntity>

    @Query("SELECT * FROM book_assets WHERE anchorBeatId = :beatId ORDER BY orderInBeat ASC")
    suspend fun getAssetsForBeat(beatId: Long): List<BookAssetEntity>

    @Insert
    suspend fun insertAssets(entities: List<BookAssetEntity>): List<Long>

    /**
     * Called once segmentation has produced beats: each image is attached to the beat
     * whose character range contains the position it was found at.
     */
    @Query(
        """
        UPDATE book_assets SET anchorBeatId = :beatId
        WHERE bookId = :bookId AND anchorBeatId IS NULL
          AND srcChar >= :startChar AND srcChar < :endChar
        """,
    )
    suspend fun anchorAssetsToBeat(bookId: Long, beatId: Long, startChar: Int, endChar: Int)

    /** Anything past the last beat's range lands on the final beat rather than vanishing. */
    @Query("UPDATE book_assets SET anchorBeatId = :beatId WHERE bookId = :bookId AND anchorBeatId IS NULL")
    suspend fun anchorRemainingAssets(bookId: Long, beatId: Long)
}

@Dao
interface BookExportDao {

    @Query("SELECT * FROM book_exports WHERE bookId = :bookId ORDER BY createdAtMillis DESC")
    fun observeExports(bookId: Long): Flow<List<BookExportEntity>>

    @Insert
    suspend fun insertExport(entity: BookExportEntity): Long

    @Query("DELETE FROM book_exports WHERE id = :id")
    suspend fun deleteExport(id: Long)
}
