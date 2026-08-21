package com.snapaie.android.data.ai

import android.app.ActivityManager
import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.snapaie.android.data.ai.model.InstalledModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface ModelSessionState {
    data object Unloaded : ModelSessionState
    data class Loading(val label: String) : ModelSessionState
    data class Ready(val label: String) : ModelSessionState
    data class Error(val message: String) : ModelSessionState
}

/** Raised when no verified model is installed yet. */
class ModelNotInstalledException : IllegalStateException("No verified model is installed.")

/**
 * Owns the LiteRT-LM engine lifecycle: lazy-load on first inference, unload after 60s
 * idle, unload on memory trim and when the app leaves the foreground, and single-flight
 * all inference behind a mutex so concurrent callers queue instead of racing the engine.
 *
 * It is also the gate that proves a freshly downloaded model actually works: the first
 * successful load promotes it to active (retiring older versions), and a failed load
 * rolls it back so the previously working model keeps serving.
 */
class ModelSessionManager(
    private val context: Context,
    private val modelRepository: ModelRepository,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var engine: Engine? = null
    private var loadedKey: String? = null
    private var idleUnloadJob: Job? = null

    @Volatile
    private var streaming = false

    private val _state = MutableStateFlow<ModelSessionState>(ModelSessionState.Unloaded)
    val state: StateFlow<ModelSessionState> = _state.asStateFlow()

    fun isModelInstalled(): Boolean = modelRepository.isModelInstalled()

    /** RAM gate: warn on devices that will struggle with a multi-GB model. */
    fun ramWarning(): String? {
        val totalGb = totalRamGb()
        return if (totalGb < MIN_COMFORTABLE_RAM_GB) {
            "This device reports $totalGb GB RAM. On-device AI may be unstable here."
        } else {
            null
        }
    }

    /**
     * Streams raw model output for [prompt]. Single-flight: concurrent calls queue.
     * Cancelling the collector cancels generation.
     */
    fun stream(prompt: String): Flow<String> = channelFlow {
        mutex.withLock {
            idleUnloadJob?.cancel()
            streaming = true
            try {
                val active = ensureEngine()
                active.createConversation().use { conversation ->
                    conversation.sendMessageAsync(prompt)
                        .catch { error -> send("\nLiteRT-LM stream error: ${error.message}") }
                        .collect { message -> send(message.toString()) }
                }
            } finally {
                streaming = false
                scheduleIdleUnload()
            }
        }
    }.flowOn(Dispatchers.IO)

    /** Convenience non-streaming generation (chat/writing/vocab/recall engines). */
    suspend fun generate(prompt: String): String {
        val builder = StringBuilder()
        stream(prompt).collect { builder.append(it) }
        return builder.toString()
    }

    /** Called from Application.onTrimMemory at TRIM_MEMORY_RUNNING_LOW or worse. */
    fun onMemoryPressure() {
        scope.launch { unload(force = true) }
    }

    /** Called when the app process leaves the foreground (never hold weights backgrounded). */
    fun onAppBackgrounded() {
        scope.launch { unload(force = false) }
    }

    suspend fun unload(force: Boolean) {
        if (!force && streaming) return
        if (force || mutex.tryLock()) {
            try {
                closeEngine()
            } finally {
                if (!force) mutex.unlock()
            }
        }
    }

    private suspend fun ensureEngine(): Engine {
        val record = modelRepository.activeRecord() ?: throw ModelNotInstalledException()
        val key = "${record.modelId}@${record.version}"
        val current = engine
        if (current != null && loadedKey == key) return current

        closeEngine()
        val label = "${record.modelId} ${record.version}"
        _state.value = ModelSessionState.Loading(label)

        val file = modelRepository.activeModelFile()
        if (file == null || !file.isFile) {
            modelRepository.onModelLoadFailed(record.modelId, record.version)
            _state.value = ModelSessionState.Error("Model file is missing.")
            throw ModelNotInstalledException()
        }

        return withContext(Dispatchers.IO) {
            runCatching {
                Engine(
                    EngineConfig(
                        modelPath = file.absolutePath,
                        backend = Backend.GPU(),
                        cacheDir = context.cacheDir.absolutePath,
                    ),
                ).also { it.initialize() }
            }.fold(
                onSuccess = { created ->
                    engine = created
                    loadedKey = key
                    _state.value = ModelSessionState.Ready(label)
                    // Proven loadable: promote it and retire older versions.
                    promoteIfNeeded(record)
                    created
                },
                onFailure = { error ->
                    // Rollback: discard this artifact so the previous model keeps serving.
                    modelRepository.onModelLoadFailed(record.modelId, record.version)
                    _state.value = ModelSessionState.Error(
                        error.message ?: "The model failed to load and was removed.",
                    )
                    throw IllegalStateException("LiteRT-LM engine failed to initialize", error)
                },
            )
        }
    }

    private fun promoteIfNeeded(record: InstalledModel) {
        if (!record.loadVerified) {
            modelRepository.onModelLoadSucceeded(record.modelId, record.version)
        }
    }

    private fun scheduleIdleUnload() {
        idleUnloadJob?.cancel()
        idleUnloadJob = scope.launch {
            delay(IDLE_UNLOAD_MS)
            unload(force = false)
        }
    }

    private fun closeEngine() {
        runCatching { engine?.close() }
        engine = null
        loadedKey = null
        _state.value = ModelSessionState.Unloaded
    }

    private fun totalRamGb(): Int {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return (memoryInfo.totalMem / 1_000_000_000L).toInt().coerceAtLeast(1)
    }

    private companion object {
        const val IDLE_UNLOAD_MS = 60_000L
        const val MIN_COMFORTABLE_RAM_GB = 4
    }
}
