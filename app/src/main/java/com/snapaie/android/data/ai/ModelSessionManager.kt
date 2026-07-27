package com.snapaie.android.data.ai

import android.app.ActivityManager
import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.snapaie.android.data.model.ModelTier
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
    data class Loading(val tier: ModelTier) : ModelSessionState
    data class Ready(val tier: ModelTier) : ModelSessionState
    data class Error(val message: String) : ModelSessionState
}

/**
 * Owns the LiteRT-LM engine lifecycle (PDF "model lifecycle — critical" rules):
 * lazy-load on first inference, unload after 60s idle, unload on memory trim and
 * when the app leaves the foreground, and single-flight all inference behind a
 * mutex so concurrent callers queue instead of racing the engine.
 */
class ModelSessionManager(
    private val context: Context,
    private val modelRepository: ModelRepository,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var engine: Engine? = null
    private var loadedTier: ModelTier? = null
    private var idleUnloadJob: Job? = null
    @Volatile private var streaming = false

    private val _state = MutableStateFlow<ModelSessionState>(ModelSessionState.Unloaded)
    val state: StateFlow<ModelSessionState> = _state.asStateFlow()

    fun isModelDownloaded(tier: ModelTier): Boolean = modelRepository.modelFile(tier).exists()

    /** RAM gate per PDF: warn below ~4GB total, steer away from the larger tier below 6GB. */
    fun ramWarning(tier: ModelTier): String? {
        val totalGb = totalRamGb()
        return when {
            totalGb < 4 -> "This device reports ${totalGb} GB RAM. On-device AI may be unstable; the smaller model is strongly recommended."
            tier == ModelTier.Gemma3nE4B && totalGb < 6 ->
                "The larger model needs about 6 GB RAM; this device reports ${totalGb} GB. Use the smaller model instead."
            else -> null
        }
    }

    /**
     * Streams raw model output for [prompt]. Single-flight: concurrent calls queue.
     * The flow completes when generation ends; cancelling the collector cancels generation.
     */
    fun stream(prompt: String, tier: ModelTier): Flow<String> = channelFlow {
        mutex.withLock {
            idleUnloadJob?.cancel()
            streaming = true
            try {
                val active = ensureEngine(tier)
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
    suspend fun generate(prompt: String, tier: ModelTier): String {
        val builder = StringBuilder()
        stream(prompt, tier).collect { builder.append(it) }
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

    private suspend fun ensureEngine(tier: ModelTier): Engine {
        val current = engine
        if (current != null && loadedTier == tier) return current
        closeEngine()
        _state.value = ModelSessionState.Loading(tier)
        return withContext(Dispatchers.IO) {
            runCatching {
                Engine(
                    EngineConfig(
                        modelPath = modelRepository.modelFile(tier).absolutePath,
                        backend = Backend.GPU(),
                        cacheDir = context.cacheDir.absolutePath,
                    ),
                ).also { it.initialize() }
            }.fold(
                onSuccess = {
                    engine = it
                    loadedTier = tier
                    _state.value = ModelSessionState.Ready(tier)
                    it
                },
                onFailure = { error ->
                    _state.value = ModelSessionState.Error(error.message ?: "Model failed to load.")
                    throw IllegalStateException("LiteRT-LM engine failed to initialize", error)
                },
            )
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
        loadedTier = null
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
    }
}
