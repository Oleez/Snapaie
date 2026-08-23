package com.snapaie.android.data.ai.download

/** Lifecycle of a model download, from first tap to a verified install. */
enum class ModelDownloadStatus {
    /** Nothing in flight and nothing partially fetched. */
    IDLE,

    /**
     * Enqueued, but WorkManager is holding it until its constraints are met — in practice
     * always "waiting for Wi-Fi". Distinct from CHECKING because a blocked transfer that
     * renders as a spinner is indistinguishable from a hung one, and the user has no way
     * to know the fix is in their own hands.
     */
    QUEUED,

    /** Inspecting local state: is it already installed, is there a resumable part file. */
    CHECKING,

    /** Preflight: configuration validity, disk space, network handshake. */
    PREPARING,

    /** Bytes are moving. */
    DOWNLOADING,

    /** Full file fetched; hashing it. */
    VERIFYING,

    /** Hash matched; moving into place atomically and writing the install marker. */
    INSTALLING,

    /** Installed and verified. */
    COMPLETED,

    /** User paused, or the worker stopped with the part file intact. Resumable. */
    PAUSED,

    /** Terminal failure; [ModelDownloadState.error] explains why. */
    FAILED,

    /** User cancelled; partial data discarded. */
    CANCELLED,
    ;

    val isActive: Boolean
        get() = this == QUEUED || this == CHECKING || this == PREPARING ||
            this == DOWNLOADING || this == VERIFYING || this == INSTALLING

    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED
}

/** Why a download failed, so the UI can say something useful instead of a raw message. */
enum class ModelDownloadError {
    NONE,

    /** The artifact is not fully described in build config; nothing was attempted. */
    NOT_CONFIGURED,

    /** Not enough free space for the model plus headroom. */
    INSUFFICIENT_STORAGE,

    /** Connectivity lost or the request timed out. Resumable. */
    NETWORK,

    /** Server rejected the request (4xx/5xx), including auth-gated artifacts. */
    HTTP,

    /** Downloaded byte count did not match the expected size. */
    SIZE_MISMATCH,

    /** SHA-256 did not match; the file was discarded. */
    CHECKSUM_MISMATCH,

    /** Could not write to storage or finalise the file. */
    STORAGE_IO,

    UNKNOWN,
}

/**
 * Observable download state. Owned by [ModelDownloadController]; the UI only reads it,
 * so an Activity or process restart can reattach to a download already in flight.
 */
data class ModelDownloadState(
    val status: ModelDownloadStatus = ModelDownloadStatus.IDLE,
    val modelId: String = "",
    val displayName: String = "",
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val bytesPerSecond: Long = 0L,
    val error: ModelDownloadError = ModelDownloadError.NONE,
    val errorMessage: String? = null,
    /** Whether this transfer was queued with an unmetered-network constraint. */
    val wifiOnly: Boolean = false,
) {
    /** Enqueued but held back, almost always because Wi-Fi was required and is absent. */
    val isWaitingForNetwork: Boolean
        get() = status == ModelDownloadStatus.QUEUED

    /** 0f..1f; 0 when the total is unknown. */
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)

    val percent: Int
        get() = (fraction * 100f).toInt().coerceIn(0, 100)

    /** Seconds remaining at the current rate, or null when it cannot be estimated. */
    val etaSeconds: Long?
        get() {
            if (bytesPerSecond <= 0L || totalBytes <= 0L) return null
            val remaining = totalBytes - downloadedBytes
            return if (remaining <= 0L) 0L else remaining / bytesPerSecond
        }

    val isResumable: Boolean
        get() = (status == ModelDownloadStatus.PAUSED || status == ModelDownloadStatus.FAILED) &&
            downloadedBytes > 0L
}
