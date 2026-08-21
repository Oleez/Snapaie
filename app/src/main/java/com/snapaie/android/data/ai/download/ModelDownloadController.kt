package com.snapaie.android.data.ai.download

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.snapaie.android.data.ai.model.ModelRegistry
import com.snapaie.android.data.ai.model.ModelSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single owner of download state and the only thing allowed to run a transfer.
 *
 * The UI observes [state] and asks this class to start/pause/cancel; it never runs the
 * download itself, so an Activity or whole-process restart can reattach to work already
 * in flight. A mutex plus WorkManager's unique-work policy means two taps (or a retry
 * racing a manual start) can never write to the same `.part` file concurrently.
 */
class ModelDownloadController(
    private val context: Context,
    private val registry: ModelRegistry,
    private val downloader: ModelDownloader,
) {
    private val mutex = Mutex()
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(ModelDownloadState())
    val state: StateFlow<ModelDownloadState> = _state.asStateFlow()

    /** The spec the current/last transfer refers to, so notification actions need no args. */
    @Volatile
    private var activeSpec: ModelSpec? = null

    /** Rebuilds state from disk after a process restart, so the UI can reattach. */
    fun reconcile(spec: ModelSpec?) {
        activeSpec = spec
        if (spec == null) {
            _state.value = ModelDownloadState()
            return
        }
        if (registry.isInstalled(spec)) {
            _state.value = ModelDownloadState(
                status = ModelDownloadStatus.COMPLETED,
                modelId = spec.modelId,
                displayName = spec.displayName,
                downloadedBytes = spec.expectedBytes,
                totalBytes = spec.expectedBytes,
            )
            return
        }
        val partial = registry.partialBytes(spec)
        _state.value = ModelDownloadState(
            status = if (partial > 0L) ModelDownloadStatus.PAUSED else ModelDownloadStatus.IDLE,
            modelId = spec.modelId,
            displayName = spec.displayName,
            downloadedBytes = partial,
            totalBytes = spec.expectedBytes,
        )
    }

    /** Enqueues the download as unique background work. Safe to call repeatedly. */
    fun start(spec: ModelSpec) {
        activeSpec = spec
        prefs.edit().putBoolean(KEY_PAUSED, false).putBoolean(KEY_CANCELLED, false).apply()
        _state.value = _state.value.copy(
            status = ModelDownloadStatus.CHECKING,
            modelId = spec.modelId,
            displayName = spec.displayName,
            totalBytes = spec.expectedBytes,
            downloadedBytes = registry.partialBytes(spec),
            error = ModelDownloadError.NONE,
            errorMessage = null,
        )
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(
                workDataOf(
                    ModelDownloadWorker.KEY_MODEL_ID to spec.modelId,
                    ModelDownloadWorker.KEY_VERSION to spec.version,
                    ModelDownloadWorker.KEY_FILENAME to spec.fileName,
                    ModelDownloadWorker.KEY_URL to spec.downloadUrl,
                    ModelDownloadWorker.KEY_SIZE to spec.expectedBytes,
                    ModelDownloadWorker.KEY_SHA256 to spec.sha256,
                    ModelDownloadWorker.KEY_RUNTIME to spec.runtime,
                    ModelDownloadWorker.KEY_RUNTIME_VERSION to spec.runtimeVersion,
                ),
            )
            .addTag(WORK_TAG)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, request)
    }

    /** Stops the transfer but keeps the partial file so it can resume later. */
    fun pause() {
        prefs.edit().putBoolean(KEY_PAUSED, true).apply()
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
        _state.value = _state.value.copy(status = ModelDownloadStatus.PAUSED, bytesPerSecond = 0L)
    }

    /** Stops the transfer and discards partial data. */
    fun cancel(spec: ModelSpec? = activeSpec) {
        prefs.edit().putBoolean(KEY_CANCELLED, true).apply()
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
        spec?.let { registry.clearPartial(it) }
        _state.value = _state.value.copy(
            status = ModelDownloadStatus.CANCELLED,
            downloadedBytes = 0L,
            bytesPerSecond = 0L,
        )
    }

    // region Worker-facing API

    /**
     * Runs the transfer. Called only by [ModelDownloadWorker]; the mutex ensures a single
     * in-process transfer even if WorkManager somehow overlaps runs.
     */
    suspend fun run(spec: ModelSpec, onProgress: (ModelDownloadState) -> Unit): ModelDownloadState =
        mutex.withLock {
            val terminal = downloader.execute(spec) { progress ->
                _state.value = progress
                onProgress(progress)
            }
            _state.value = terminal
            terminal
        }

    /** Called when the worker is stopped: distinguishes user pause from user cancel. */
    fun onWorkerStopped(spec: ModelSpec?) {
        val cancelled = prefs.getBoolean(KEY_CANCELLED, false)
        if (cancelled) {
            spec?.let { registry.clearPartial(it) }
            _state.value = _state.value.copy(
                status = ModelDownloadStatus.CANCELLED,
                downloadedBytes = 0L,
                bytesPerSecond = 0L,
            )
        } else {
            _state.value = _state.value.copy(
                status = ModelDownloadStatus.PAUSED,
                bytesPerSecond = 0L,
            )
        }
    }

    // endregion

    companion object {
        const val UNIQUE_WORK = "snapaie-model-download"
        const val WORK_TAG = "snapaie-model"
        private const val PREFS = "snapaie_model_download"
        private const val KEY_PAUSED = "paused"
        private const val KEY_CANCELLED = "cancelled"
    }
}
