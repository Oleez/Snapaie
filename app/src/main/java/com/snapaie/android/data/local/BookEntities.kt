package com.snapaie.android.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A source document the user wants condensed.
 *
 * [sourcePath] is a *copy* the app owns, not the incoming content URI. A condense run
 * lasts hours and can outlive the temporary read grant that arrived with a share intent,
 * so the bytes are duplicated into `files/books/<id>/` at import time.
 */
@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val author: String = "",
    /** [com.snapaie.android.data.model.BookSourceKind] name. */
    val sourceKind: String,
    val sourcePath: String = "",
    val sourcePageCount: Int = 0,
    val sourceWordCount: Int = 0,
    val sourceCharCount: Int = 0,
    val coverPath: String? = null,
    /** [com.snapaie.android.data.model.BookImportState] name. */
    val importState: String,
    val importError: String? = null,
    val createdAtMillis: Long,
)

/**
 * One chapter of the source, as a half-open character range `[srcStartChar, srcEndChar)`
 * over the extracted text. Chapters come from a PDF outline or EPUB nav where one exists,
 * and from heading heuristics where it does not.
 */
@Entity(
    tableName = "book_chapters",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("bookId")],
)
data class BookChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val bookId: Long,
    val orderIndex: Int,
    val title: String,
    val srcStartChar: Int,
    val srcEndChar: Int,
    val srcPageFrom: Int = 0,
    /** Filled in by the layout engine once the output has been paginated. */
    val outputStartPage: Int? = null,
)

/**
 * The unit of work, and the reason nothing can be skipped.
 *
 * Beats tile their chapter exactly: every character of the source belongs to precisely one
 * beat, and a condense run is complete only when no beat is still PENDING or RUNNING. That
 * makes "did we cover the whole book?" a database query rather than a hope.
 *
 * [pass] carries the ladder: pass 1 condenses the source, pass 2 (only for very small
 * targets) re-condenses pass 1's output, so beats from different passes coexist.
 */
@Entity(
    tableName = "book_beats",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("bookId"),
        Index("chapterId"),
        Index(value = ["bookId", "pass", "orderIndex"], unique = true),
    ],
)
data class BookBeatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val bookId: Long,
    val chapterId: Long,
    val pass: Int = 1,
    val orderIndex: Int,
    val srcStartChar: Int,
    val srcEndChar: Int,
    val srcWords: Int,
    val srcPageFrom: Int = 0,
    val srcPageTo: Int = 0,
    /** [com.snapaie.android.data.model.BeatStatus] name. */
    val status: String,
    val attempts: Int = 0,
    val targetWords: Int = 0,
    val outputText: String = "",
    val outputWords: Int = 0,
    /** Story ledger as it stood *after* this beat, so a resume can pick up continuity. */
    val ledgerJson: String = "",
    val errorMessage: String? = null,
)

/**
 * An image lifted out of the source, anchored to the beat whose text surrounded it.
 *
 * Anchoring to a beat rather than to a source page is what lets figures survive
 * condensation: the output has different pagination, but it still has the same beats in
 * the same order, so the picture lands next to the passage it belonged to.
 */
@Entity(
    tableName = "book_assets",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("bookId"), Index("anchorBeatId")],
)
data class BookAssetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val bookId: Long,
    /** [com.snapaie.android.data.model.BookAssetKind] name. */
    val kind: String,
    val path: String,
    val anchorBeatId: Long? = null,
    val srcPage: Int = 0,
    /** Character offset in the source text, used to resolve [anchorBeatId] after segmentation. */
    val srcChar: Int = 0,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val captionText: String = "",
    val orderInBeat: Int = 0,
)

/** One condense run over one book at one ladder pass. */
@Entity(
    tableName = "condense_jobs",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("bookId")],
)
data class CondenseJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val bookId: Long,
    /** [com.snapaie.android.data.model.CondenseTargetKind] name. */
    val targetKind: String,
    val targetValue: Int,
    val pass: Int = 1,
    /** [com.snapaie.android.data.model.CondenseJobState] name. */
    val state: String,
    val targetWords: Int = 0,
    val producedWords: Int = 0,
    val chargingOnly: Boolean = true,
    val startedAtMillis: Long = 0L,
    val finishedAtMillis: Long? = null,
    val errorMessage: String? = null,
)

/** A file written out of a finished book. */
@Entity(
    tableName = "book_exports",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("bookId")],
)
data class BookExportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val bookId: Long,
    /** [com.snapaie.android.data.model.BookExportFormat] name. */
    val format: String,
    val path: String,
    val pageCount: Int = 0,
    val createdAtMillis: Long,
)
