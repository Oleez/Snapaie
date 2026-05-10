package com.snapae.android.data.ai

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import com.snapae.android.data.model.ModelSetupState
import com.snapae.android.data.model.ModelTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class ModelRepository(
    private val context: Context,
    private val client: OkHttpClient,
) {
    private val modelsDir = File(context.filesDir, "models").also { it.mkdirs() }
    private val _state = MutableStateFlow(currentState(ModelTier.Gemma4E2B))
    val state: StateFlow<ModelSetupState> = _state

    fun modelFile(tier: ModelTier): File = File(modelsDir, tier.fileName)

    fun selectTier(tier: ModelTier) {
        _state.value = currentState(tier)
    }

    suspend fun downloadSelected() = withContext(Dispatchers.IO) {
        val tier = _state.value.selectedTier
        val destination = modelFile(tier)
        val partial = File(destination.absolutePath + ".part")
        _state.update { currentState(tier).copy(isDownloading = true) }

        val request = Request.Builder()
            .url(tier.downloadUrl)
            .apply {
                if (partial.exists()) header("Range", "bytes=${partial.length()}-")
            }
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                require(response.isSuccessful || response.code == 206) {
                    "Download failed with HTTP ${response.code}"
                }
                val append = response.code == 206 && partial.exists()
                if (!append && partial.exists()) partial.delete()
                val body = requireNotNull(response.body)
                val total = body.contentLength().takeIf { it > 0L }?.plus(partial.length())
                    ?: tier.estimatedBytes
                body.byteStream().use { input ->
                    FileOutputStream(partial, append).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            _state.update {
                                it.copy(
                                    downloadedBytes = partial.length(),
                                    totalBytes = total,
                                    isDownloading = true,
                                )
                            }
                        }
                    }
                }
            }
            if (tier.sha256.isNotBlank()) {
                require(sha256(partial).equals(tier.sha256, ignoreCase = true)) {
                    "Checksum mismatch for ${tier.displayName}"
                }
            }
            partial.renameTo(destination)
            _state.value = currentState(tier)
        }.onFailure { error ->
            _state.update {
                it.copy(isDownloading = false, warning = error.message ?: "Download failed")
            }
        }
    }

    private fun currentState(tier: ModelTier): ModelSetupState {
        val file = modelFile(tier)
        val freeBytes = StatFs(modelsDir.absolutePath).availableBytes
        val ramGb = totalRamGb()
        val warnings = buildList {
            if (freeBytes < tier.estimatedBytes + 1_000_000_000L) {
                add("Storage is tight for ${tier.displayName}. Free at least 1 GB beyond the model.")
            }
            if (ramGb < tier.recommendedRamGb) {
                add("This device reports about ${ramGb} GB RAM; ${tier.recommendedRamGb} GB is recommended.")
            }
        }
        return ModelSetupState(
            selectedTier = tier,
            downloadedBytes = if (file.exists()) file.length() else 0L,
            totalBytes = tier.estimatedBytes,
            isReady = file.exists(),
            warning = warnings.firstOrNull(),
        )
    }

    private fun totalRamGb(): Int {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return (memoryInfo.totalMem / 1_000_000_000L).toInt().coerceAtLeast(1)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
