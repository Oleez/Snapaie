package com.snapaie.android.data.ai

import android.app.ActivityManager
import android.content.Context
import com.snapaie.android.data.ai.download.ModelDownloadController
import com.snapaie.android.data.ai.download.ModelDownloadState
import com.snapaie.android.data.ai.download.ModelDownloadStatus
import com.snapaie.android.data.ai.model.InstalledModel
import com.snapaie.android.data.ai.model.ModelManifestRepository
import com.snapaie.android.data.ai.model.ModelRegistry
import com.snapaie.android.data.ai.model.ModelSpec
import com.snapaie.android.data.ai.model.ModelUpdateStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Everything the UI needs to know about the offline model, in one snapshot. */
data class ModelUiState(
    val installed: InstalledModel? = null,
    val updateStatus: ModelUpdateStatus = ModelUpdateStatus.NotConfigured,
    val download: ModelDownloadState = ModelDownloadState(),
    val isCheckingManifest: Boolean = false,
    val ramWarning: String? = null,
) {
    /** True once a verified model is on disk — inference can run fully offline. */
    val isModelInstalled: Boolean get() = installed != null

    val isBusy: Boolean get() = download.status.isActive

    /** The artifact the user can fetch right now, if any. */
    val downloadableSpec: ModelSpec?
        get() = when (val status = updateStatus) {
            is ModelUpdateStatus.FirstInstallAvailable -> status.spec
            is ModelUpdateStatus.UpdateAvailable -> status.spec
            else -> null
        }

    val hasUpdateAvailable: Boolean get() = updateStatus is ModelUpdateStatus.UpdateAvailable
}

/**
 * Façade over model delivery: what is installed, what the manifest offers, and the
 * state of any download. The rest of the app talks to this rather than to the registry,
 * manifest repository, or download controller directly.
 */
class ModelRepository(
    private val context: Context,
    private val registry: ModelRegistry,
    private val manifestRepository: ModelManifestRepository,
    private val downloadController: ModelDownloadController,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(ModelUiState())
    val state: StateFlow<ModelUiState> = _state.asStateFlow()

    val downloadState: StateFlow<ModelDownloadState> = downloadController.state

    init {
        refreshLocal()
        // Reflect download progress into the aggregate state, and refresh the install
        // record the moment a download finishes verifying.
        scope.launch {
            downloadController.state.collect { download ->
                _state.value = _state.value.copy(download = download)
                if (download.status == ModelDownloadStatus.COMPLETED) refreshLocal()
            }
        }
    }

    /** The verified model the engine should load, or null when nothing is installed. */
    fun activeRecord(): InstalledModel? = registry.activeRecord()

    fun activeModelFile(): File? = registry.activeRecord()?.let { registry.modelFile(it) }

    fun isModelInstalled(): Boolean = registry.activeRecord() != null

    /** Re-reads local install state without touching the network. */
    fun refreshLocal() {
        val installed = registry.activeRecord()
        _state.value = _state.value.copy(
            installed = installed,
            ramWarning = ramWarning(),
        )
    }

    /**
     * Checks the manifest and updates [state]. Never throws; if the check fails the
     * installed model keeps working and the status records why.
     */
    suspend fun checkForUpdate(force: Boolean = false) {
        if (!manifestRepository.isConfigured) {
            _state.value = _state.value.copy(updateStatus = ModelUpdateStatus.NotConfigured)
            return
        }
        _state.value = _state.value.copy(isCheckingManifest = true)
        val status = withContext(Dispatchers.IO) { manifestRepository.checkForUpdate(force) }
        _state.value = _state.value.copy(
            updateStatus = status,
            installed = registry.activeRecord(),
            isCheckingManifest = false,
        )
        // Let the controller reattach to any partial download for the offered artifact.
        downloadController.reconcile(_state.value.downloadableSpec)
    }

    /**
     * Safe to call on every app start: [checkForUpdate] hits the network only when the
     * throttle window has elapsed, and otherwise resolves the cached manifest so the UI
     * still knows what is available offline.
     */
    fun checkForUpdateIfDue() {
        scope.launch { checkForUpdate(force = false) }
    }

    /** Starts (or resumes) the download of the currently offered artifact. */
    fun startDownload(): Boolean {
        val spec = _state.value.downloadableSpec ?: return false
        downloadController.start(spec)
        return true
    }

    fun pauseDownload() = downloadController.pause()

    fun cancelDownload() {
        downloadController.cancel(_state.value.downloadableSpec)
    }

    /**
     * Called by the session manager once the engine has actually loaded an artifact.
     * Only now does the new model become active and older versions get removed.
     */
    fun onModelLoadSucceeded(modelId: String, version: String) {
        registry.promoteToActive(modelId, version)
        refreshLocal()
    }

    /**
     * Rollback: the engine could not load this artifact. It is deleted and the previously
     * active model is left untouched.
     */
    fun onModelLoadFailed(modelId: String, version: String) {
        registry.rejectInstall(modelId, version)
        refreshLocal()
    }

    /** Wipes all downloaded artifacts (factory reset / free space). */
    suspend fun deleteAllWeights() = withContext(Dispatchers.IO) {
        registry.clearAll()
        refreshLocal()
    }

    private fun ramWarning(): String? {
        val totalGb = totalRamGb()
        return if (totalGb < MIN_COMFORTABLE_RAM_GB) {
            "This device reports about $totalGb GB RAM. On-device AI may be slow or unstable here."
        } else {
            null
        }
    }

    private fun totalRamGb(): Int {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return (memoryInfo.totalMem / 1_000_000_000L).toInt().coerceAtLeast(1)
    }

    private companion object {
        const val MIN_COMFORTABLE_RAM_GB = 4
    }
}
