package com.snapaie.android.data.ai

import android.app.ActivityManager
import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.snapaie.android.core.diagnostics.CrashLog
import com.snapaie.android.data.ai.model.InstalledModel
import com.snapaie.android.data.ai.model.ModelBackend
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger

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
 * Two things it deliberately does *not* do:
 *
 *  - It does not assume GPU. `Backend.GPU()` needs a vendor OpenCL driver that plenty of
 *    devices lack, so a failed load is retried on CPU before the artifact is blamed.
 *  - It does not throw away a first install. Rejecting an artifact deletes gigabytes, and
 *    that is only ever the right move when a previously-working model exists to fall back
 *    to; see [ModelRepository.onModelLoadFailed].
 */
class ModelSessionManager(
    private val context: Context,
    private val modelRepository: ModelRepository,
    private val scope: CoroutineScope,
    private val visionGuard: VisionGuard = VisionGuard(context),
) {

    /** False once reading images has proven fatal on this device. */
    val visionAllowed: Boolean get() = visionGuard.isVisionAllowed
    private val mutex = Mutex()
    private var engine: Engine? = null
    private var loadedKey: String? = null
    private var idleUnloadJob: Job? = null

    /** Non-zero while a long-running job (e.g. a book condense) needs the engine resident. */
    private val keepAlive = AtomicInteger(0)

    /**
     * The coroutine currently generating, so an urgent unload can stop it *before* freeing
     * the weights it is reading from.
     */
    @Volatile
    private var activeGeneration: Job? = null

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
     * Pins the engine in memory for the duration of a long job.
     *
     * A book condense runs for hours, backgrounded by definition, so both the 60s idle
     * unloader and the leave-the-foreground unload would otherwise tear the engine down
     * between beats and pay the multi-second reload every time. Memory pressure still
     * wins — it has to, or the OS kills the process instead.
     */
    fun acquireKeepAlive(): Closeable {
        keepAlive.incrementAndGet()
        idleUnloadJob?.cancel()
        return Closeable {
            if (keepAlive.decrementAndGet() <= 0) {
                keepAlive.set(0)
                scheduleIdleUnload()
            }
        }
    }

    /**
     * Streams raw model output for [prompt]. Single-flight: concurrent calls queue.
     * Cancelling the collector cancels generation.
     */
    /**
     * Streams a reply to [prompt] with [imagePath] attached, letting the model read the
     * picture itself.
     *
     * Gemma 4 E2B takes images as well as text, which matters for pages a plain text
     * recogniser struggles with — a curved spine, a column that wraps oddly, a footnote
     * glued to the body. The recogniser is still tried first because it is far faster and
     * usually right; this is the fallback for when it is not.
     */
    fun streamWithImage(
        prompt: String,
        imagePath: String,
        maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
    ): Flow<String> = stream(prompt, imagePath, maxOutputTokens)

    fun stream(
        prompt: String,
        maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
    ): Flow<String> = stream(prompt, null, maxOutputTokens)

    private fun stream(
        prompt: String,
        imagePath: String?,
        maxOutputTokens: Int,
    ): Flow<String> = channelFlow {
        mutex.withLock {
            idleUnloadJob?.cancel()
            streaming = true
            try {
                val active = ensureEngine()
                // Two things decide how long a reply takes: how much context has to be
                // read, and how many tokens come back. The second was unbounded, so a
                // model that failed to stop ran until the caller's timeout — minutes of
                // work for an answer nobody wanted that long. Greedy sampling on top,
                // because this is extraction and condensation, not creative writing, and
                // there is nothing to gain from sampling a distribution.
                active.createConversation(
                    ConversationConfig(
                        samplerConfig = SamplerConfig(
                            topK = 1,
                            topP = 1.0,
                            temperature = 0.0,
                        ),
                        maxOutputToken = maxOutputTokens,
                    ),
                ).use { conversation ->
                    val withImage = imagePath != null && visionGuard.isVisionAllowed
                    val request = if (withImage) {
                        Contents.of(Content.ImageFile(imagePath!!), Content.Text(prompt))
                    } else {
                        Contents.of(prompt)
                    }
                    // Flag raised before the handover and lowered after, so a process that
                    // never comes back is detectable at the next launch.
                    if (withImage) visionGuard.beginVisionCall()
                    conversation.sendMessageAsync(request)
                        .catch { error -> send("\nLiteRT-LM stream error: ${error.message}") }
                        .collect { message -> send(message.toString()) }
                }
            } finally {
                streaming = false
                scheduleIdleUnload()
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Generation for callers that already have a sensible answer for "no usable output".
     *
     * Recall cards, vocabulary and scoring all fall back cleanly when the model returns
     * something unparseable, and an engine that failed to load is the same situation from
     * their point of view — so it should not arrive as an exception thrown into whatever
     * coroutine the screen happened to launch.
     */
    suspend fun generateOrEmpty(prompt: String): String =
        runCatching { generate(prompt) }.getOrDefault("")

    /** Convenience non-streaming generation (chat/writing/vocab/recall engines). */
    suspend fun generate(prompt: String): String {
        val builder = StringBuilder()
        stream(prompt).collect { builder.append(it) }
        return builder.toString()
    }

    /**
     * Called from Application.onTrimMemory.
     *
     * [urgent] separates "the system is a bit tight" from "we are about to be killed". At
     * the milder level a generation in flight is left alone and the engine is dropped once
     * it finishes, because tearing down mid-sentence to reclaim memory we are not yet being
     * asked for costs the user their answer for nothing.
     */
    fun onMemoryPressure(urgent: Boolean) {
        scope.launch { if (urgent) unload(force = true) else unload(force = false) }
    }

    /** Called when the app process leaves the foreground (never hold weights backgrounded). */
    fun onAppBackgrounded() {
        if (keepAlive.get() > 0) return
        scope.launch { unload(force = false) }
    }

    /**
     * Releases the engine.
     *
     * The close must never overlap a generation. Freeing the weights while native code is
     * still reading them is a use-after-free, which arrives as a process-level SIGSEGV that
     * no runCatching in the UI can survive — and the old forced path did exactly that,
     * closing outside the mutex whenever memory got tight, which is precisely when a
     * multi-GB model is resident and someone is generating text.
     *
     * So a forced unload now cancels the generation first and then takes the same mutex the
     * generation holds. Acquiring it is the proof that nothing is in flight. If the native
     * side will not come back promptly we give up and keep the memory rather than close
     * underneath it; a heavy process beats a dead one.
     */
    suspend fun unload(force: Boolean) {
        if (!force) {
            if (streaming) return
            if (mutex.tryLock()) {
                try {
                    closeEngine()
                } finally {
                    mutex.unlock()
                }
            }
            return
        }

        activeGeneration?.cancel()
        val closed = withTimeoutOrNull(FORCED_UNLOAD_TIMEOUT_MS) {
            mutex.withLock { closeEngine() }
            true
        }
        if (closed != null) return

        // Giving up here used to mean the weights stayed resident for the rest of the
        // session — so a long stretch of use ended in the process being killed for memory.
        // Keep trying in the background instead; the generation has already been cancelled,
        // so the lock is only a matter of time.
        scope.launch {
            repeat(FORCED_UNLOAD_RETRIES) {
                delay(FORCED_UNLOAD_TIMEOUT_MS)
                val done = withTimeoutOrNull(FORCED_UNLOAD_TIMEOUT_MS) {
                    mutex.withLock { closeEngine() }
                    true
                }
                if (done != null) return@launch
            }
        }
    }

    private fun friendly(error: Throwable): String = when {
        error is ModelNotInstalledException -> "offline AI is not downloaded yet"
        error.message?.contains("memory", ignoreCase = true) == true -> "this device ran out of memory"
        else -> "something went wrong. Try again."
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
            // The bytes are already gone; drop the record so the UI offers a re-download.
            modelRepository.onModelFileMissing(record.modelId, record.version)
            _state.value = ModelSessionState.Error("Model file is missing.")
            throw ModelNotInstalledException()
        }

        val attempts = backendAttempts(record)
        var lastError: Throwable? = null

        for (backend in attempts) {
            val created = withContext(Dispatchers.IO) {
                runCatching {
                    Engine(
                        EngineConfig(
                            modelPath = file.absolutePath,
                            backend = backend.toRuntimeBackend(),
                            // Vision backend deliberately left at its default. Pinning it
                            // to the text backend meant a build whose image encoder cannot
                            // run there failed to initialise at all — taking every feature
                            // down, not just the ones that read pictures.
                            // Both of these were wrong and both crash natively rather
                            // than throwing. maxNumImages was never set, so the engine was
                            // built with no image buffers and then handed a picture. And
                            // 2K of context cannot hold an encoded image plus a prompt plus
                            // a reply — a vision encoder emits hundreds of tokens per tile,
                            // and overflowing the window corrupts memory instead of
                            // reporting anything.
                            maxNumImages = if (visionAllowed) MAX_IMAGES else 0,
                            maxNumTokens = if (visionAllowed) VISION_CONTEXT_TOKENS else MAX_CONTEXT_TOKENS,
                            cacheDir = context.cacheDir.absolutePath,
                        ),
                    ).also {
                        CrashLog.breadcrumb("loading offline AI on $backend")
                        it.initialize()
                    }
                }
            }.getOrElse { error ->
                lastError = error
                null
            }

            if (created != null) {
                engine = created
                loadedKey = key
                _state.value = ModelSessionState.Ready(label)
                if (record.loadedBackend != backend.wireName) {
                    modelRepository.onBackendResolved(record.modelId, record.version, backend)
                }
                // Proven loadable: promote it and retire older versions.
                promoteIfNeeded(record)
                return created
            }
        }

        val message = lastError?.message
            ?: "The model could not be loaded on this device."
        modelRepository.onModelLoadFailed(record.modelId, record.version)
        _state.value = ModelSessionState.Error(message)
        throw IllegalStateException("LiteRT-LM engine failed to initialize on ${attempts.joinToString()}", lastError)
    }

    /**
     * Backends to try, best guess first: whatever last worked, else what the manifest
     * variant targeted, then the other one. CPU is always in the list because it is the
     * only backend with no driver prerequisite.
     */
    private fun backendAttempts(record: InstalledModel): List<ModelBackend> {
        val first = ModelBackend.fromWire(record.loadedBackend ?: record.backend)
        return (listOf(first) + ModelBackend.entries).distinct()
    }

    private fun ModelBackend.toRuntimeBackend(): Backend = when (this) {
        ModelBackend.GPU -> Backend.GPU()
        ModelBackend.CPU -> Backend.CPU()
    }

    private fun promoteIfNeeded(record: InstalledModel) {
        if (!record.loadVerified) {
            modelRepository.onModelLoadSucceeded(record.modelId, record.version)
        }
    }

    private fun scheduleIdleUnload() {
        if (keepAlive.get() > 0) return
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

    companion object {
        const val IDLE_UNLOAD_MS = 60_000L
        const val MIN_COMFORTABLE_RAM_GB = 4

        /** How long a forced unload waits for an in-flight generation to stop. */
        const val FORCED_UNLOAD_TIMEOUT_MS = 4_000L
        const val FORCED_UNLOAD_RETRIES = 10

        /**
         * Context window the engine is built with. Every prompt this app sends fits well
         * inside it, and a smaller window means faster prefill and less resident memory.
         */
        const val MAX_CONTEXT_TOKENS = 2_048

        /** Room for an encoded page image alongside the prompt and the reply. */
        const val VISION_CONTEXT_TOKENS = 4_096
        const val MAX_IMAGES = 1

        /** Roughly 400 words, which is more than any single answer here needs. */
        const val DEFAULT_MAX_OUTPUT_TOKENS = 560
    }
}
