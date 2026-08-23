package com.snapaie.android.data.model

/** Where a book's text came from. */
enum class BookSourceKind {
    PDF,
    EPUB,
    SCAN,
    TEXT,
    ;

    companion object {
        fun fromStored(value: String?): BookSourceKind =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: TEXT
    }
}

/** Progress of turning a source file into chapters and beats. */
enum class BookImportState {
    IMPORTING,
    READY,
    FAILED,
    ;

    companion object {
        fun fromStored(value: String?): BookImportState =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: IMPORTING
    }
}

/**
 * Lifecycle of a single beat.
 *
 * [CONDENSED] and [FALLBACK] are both terminal successes — the difference is only how the
 * text was produced. [FAILED] exists for completeness but the pipeline is built never to
 * land there: a beat that cannot be condensed is written extractively instead, because a
 * gap in the middle of a story is worse than a clumsy paragraph.
 */
enum class BeatStatus {
    PENDING,
    RUNNING,
    CONDENSED,
    FALLBACK,
    FAILED,
    ;

    val isDone: Boolean get() = this == CONDENSED || this == FALLBACK

    companion object {
        fun fromStored(value: String?): BeatStatus =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: PENDING
    }
}

/** How the user expressed the length they want. */
enum class CondenseTargetKind {
    /** An absolute output page count, e.g. "150 pages". */
    PAGES,

    /** A fraction of the source, e.g. 30%. */
    PERCENT,
    ;

    companion object {
        fun fromStored(value: String?): CondenseTargetKind =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: PERCENT
    }
}

/** State of a whole-book condense run. */
enum class CondenseJobState {
    QUEUED,
    RUNNING,
    PAUSED,
    COMPLETED,
    CANCELLED,
    FAILED,
    ;

    val isActive: Boolean get() = this == QUEUED || this == RUNNING || this == PAUSED

    companion object {
        fun fromStored(value: String?): CondenseJobState =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: QUEUED
    }
}

/** What a stored asset is. */
enum class BookAssetKind {
    IMAGE,
    COVER,
    ;

    companion object {
        fun fromStored(value: String?): BookAssetKind =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: IMAGE
    }
}

/** Formats a condensed book can be written out as. */
enum class BookExportFormat(val extension: String, val mimeType: String) {
    PDF("pdf", "application/pdf"),
    EPUB("epub", "application/epub+zip"),
    MARKDOWN("md", "text/markdown"),
    TEXT("txt", "text/plain"),
    ;

    companion object {
        fun fromStored(value: String?): BookExportFormat =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: PDF
    }
}
