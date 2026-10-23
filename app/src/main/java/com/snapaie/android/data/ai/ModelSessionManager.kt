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
) : TextGenerator {

    override val visionAllowed: Boolean
        get() = modelRepository.activeRecord()?.supportsVision == true
    private val mutex = Mutex()
    private var engine: Engine? = null
    private var loadedKey: String? = null
    /** True when [engine] was initialized with the vision encoder, which costs extra RAM. */
    private var loadedVision: Boolean = false
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

    override fun isModelInstalled(): Boolean = modelRepository.isModelInstalled()

    /**
     * Pins the engine in memory for the duration of a long job.
     *
     * A book condense runs for hours, backgrounded by definition, so both the 60s idle
     * unloader and the leave-the-foreground unload would otherwise tear the engine down
     * between beats and pay the multi-second reload every time. Memory pressure still
     * wins — it has to, or the OS kills the process instead.
     */
    override fun acquireKeepAlive(): Closeable {
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
    * Streams a reply to [prompt] with [imagePath] attached for a vision-capable model.
     */
    override fun streamWithImage(
        prompt: String,
        imagePath: String,
        maxOutputTokens: Int,
    ): Flow<String> = stream(prompt, imagePath, maxOutputTokens)

    override fun stream(
        prompt: String,
        maxOutputTokens: Int,
    ): Flow<String> = stream(prompt, null, maxOutputTokens)

    /** Convenience for callers that do not care about the budget. */
    fun stream(prompt: String): Flow<String> = stream(prompt, null, DEFAULT_MAX_OUTPUT_TOKENS)

    /**
     * Text calls load the weights without the vision encoder. An image call reloads once
     * with vision, and only when enough RAM is free — a printed page that OCR already
     * read never pays for that encoder.
     */
    private fun stream(
        prompt: String,
        imagePath: String?,
        maxOutputTokens: Int,
    ): Flow<String> = channelFlow {
        mutex.withLock {
            idleUnloadJob?.cancel()
            streaming = true
            activeGeneration = currentCoroutineContext()[Job]
            try {
                if (imagePath != null) {
                    check(visionAllowed) { "The installed offline model cannot read images." }
                    check(java.io.File(imagePath).isFile) { "The page image is unavailable." }
                }
                val active = ensureEngine(withVision = imagePath != null)
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
                    val contents = if (imagePath == null) {
                        Contents.of(prompt)
                    } else {
                        Contents.of(Content.ImageFile(imagePath), Content.Text(prompt))
                    }
                    conversation.sendMessageAsync(contents)
                        // Never write the failure into the reply. It used to be sent down the
                        // same channel as the text, so the runtime error string was pasted onto
                        // the front of the retelling and shipped to the reader as part of it.
                        .catch { error -> noteFailure(error) }
                        .collect { message -> send(message.toString()) }
                }
            } finally {
                streaming = false
                activeGeneration = null
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

    /** Records why a generation stopped, for the UI to show. */
    private fun noteFailure(error: Throwable) {
        _state.value = ModelSessionState.Error(friendly(error))
    }

    private fun friendly(error: Throwable): String = when {
        error is ModelNotInstalledException -> "offline AI is not downloaded yet"
        generateSequence(error) { it.cause }
            .any { it.message?.contains("memory", ignoreCase = true) == true } ->
            "Offline AI ran out of available memory. Close other apps and try again."
        else -> "Offline AI could not load on this device. See Settings > Problem report for details."
    }

    private suspend fun ensureEngine(withVision: Boolean): Engine {
        val record = modelRepository.activeRecord() ?: throw ModelNotInstalledException()
        val wantsVision = withVision && record.supportsVision
        if (withVision && !record.supportsVision) {
            throw IllegalStateException("The installed offline model cannot read images.")
        }
        val key = "${record.modelId}@${record.version}"
        val current = engine
        if (current != null && loadedKey == key && loadedVision == wantsVision) return current

        // Check before closing. A text engine that is already resident should survive a
        // vision call that the device cannot afford.
        requireFreeRam(wantsVision)
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
                            visionBackend = if (wantsVision) backend.toRuntimeBackend() else null,
                            maxNumImages = if (wantsVision) MAX_NUM_IMAGES else 0,
                            maxNumTokens = MAX_CONTEXT_TOKENS,
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
                loadedVision = wantsVision
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
        val memoryInfo = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memoryInfo)
        CrashLog.recordModelLoadFailure(
            modelId = record.modelId,
            version = record.version,
            backends = attempts.map { it.wireName },
            totalRamBytes = memoryInfo.totalMem,
            availableRamBytes = memoryInfo.availMem,
            error = lastError ?: IllegalStateException(message),
        )
        modelRepository.onModelLoadFailed(record.modelId, record.version)
        _state.value = ModelSessionState.Error(friendly(lastError ?: IllegalStateException(message)))
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

    /**
     * Refuses to construct the native engine when the device is already short of RAM.
     *
     * Initializing anyway is what kills the process: the runtime allocates the weights
     * and the whole context before any Kotlin catch can run, and a process death never
     * reaches the "close other apps" message.
     */
    private fun requireFreeRam(withVision: Boolean) {
        val needed = if (withVision) MIN_FREE_RAM_VISION_BYTES else MIN_FREE_RAM_TEXT_BYTES
        val available = availableRamBytes()
        if (available >= needed) return
        val error = IllegalStateException(
            "Not enough free memory to load offline AI. Close other apps and try again.",
        )
        _state.value = ModelSessionState.Error(friendly(error))
        throw error
    }

    private fun availableRamBytes(): Long {
        val memoryInfo = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memoryInfo)
        return memoryInfo.availMem
    }

    private fun closeEngine() {
        runCatching { engine?.close() }
        engine = null
        loadedKey = null
        loadedVision = false
        _state.value = ModelSessionState.Unloaded
    }

    companion object {
        const val IDLE_UNLOAD_MS = 60_000L
        /** How long a forced unload waits for an in-flight generation to stop. */
        const val FORCED_UNLOAD_TIMEOUT_MS = 4_000L
        const val FORCED_UNLOAD_RETRIES = 10

        /**
         * Context window the engine is built with, counting the prompt and the reply.
         *
         * LiteRT allocates this whole window up front, so a larger number is a larger
         * resident set — and that resident set is what gets the process killed on an 8 GB
         * phone that is already running other apps. 2,048 is the size we can afford.
         * Passages longer than the window are walked in slices; [PromptBudget] derives
         * each slice from this number so a prompt cannot overflow it and silently fall
         * back to a local shortening.
         */
        const val MAX_CONTEXT_TOKENS = 2_048
        const val MAX_NUM_IMAGES = 1

        /** Free RAM required before a text-only load. Below this we never touch native init. */
        const val MIN_FREE_RAM_TEXT_BYTES = 1_600_000_000L

        /** Free RAM required before loading the vision encoder on top of the text weights. */
        const val MIN_FREE_RAM_VISION_BYTES = 2_400_000_000L

        /** Roughly 400 words, which is more than any single answer here needs. */
        const val DEFAULT_MAX_OUTPUT_TOKENS = 560
    }
}
