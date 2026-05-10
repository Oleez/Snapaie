package com.snapae.android.data.ai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.snapae.android.data.model.ModelTier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File

interface LocalInferenceEngine {
    suspend fun warmUp(tier: ModelTier): WarmUpResult
    fun stream(prompt: String, tier: ModelTier): Flow<String>
    fun cancel()
}

data class WarmUpResult(
    val ready: Boolean,
    val message: String,
)

class LiteRtLocalInferenceEngine(
    private val context: Context,
    private val modelRepository: ModelRepository,
) : LocalInferenceEngine {
    @Volatile private var cancelled = false
    private var engine: Engine? = null

    override suspend fun warmUp(tier: ModelTier): WarmUpResult {
        val modelFile = modelRepository.modelFile(tier)
        if (!modelFile.exists()) {
            return WarmUpResult(
                ready = false,
                message = "Model is not downloaded yet.",
            )
        }

        return withContext(Dispatchers.IO) {
            runCatching {
                engine?.close()
                engine = Engine(
                    EngineConfig(
                        modelPath = modelFile.absolutePath,
                        backend = Backend.GPU(),
                        cacheDir = context.cacheDir.absolutePath,
                    ),
                ).also { it.initialize() }
                WarmUpResult(
                    ready = true,
                    message = "LiteRT-LM engine is warm.",
                )
            }.getOrElse { error ->
                WarmUpResult(
                    ready = false,
                    message = error.message ?: "LiteRT-LM initialization failed.",
                )
            }
        }
    }

    override fun stream(prompt: String, tier: ModelTier): Flow<String> = flow {
        cancelled = false
        val modelFile: File = modelRepository.modelFile(tier)
        if (!modelFile.exists()) {
            emit("Model setup is required before local inference can run.")
            return@flow
        }

        val activeEngine = engine ?: Engine(
            EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.GPU(),
                cacheDir = context.cacheDir.absolutePath,
            ),
        ).also {
            emit("Warming local ${tier.displayName} runtime...\n")
            it.initialize()
            engine = it
        }

        activeEngine.createConversation().use { conversation ->
            conversation.sendMessageAsync(prompt)
                .catch { error -> emit("\nLiteRT-LM stream error: ${error.message}") }
                .collect { message ->
                    if (cancelled) throw CancellationException("Inference cancelled")
                    emit(message.toString())
                }
        }
    }

    override fun cancel() {
        cancelled = true
        engine?.close()
        engine = null
    }
}
