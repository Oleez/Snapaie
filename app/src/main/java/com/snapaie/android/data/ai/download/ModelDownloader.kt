package com.snapaie.android.data.ai.download

import com.snapaie.android.data.ai.model.ModelManifestValidator
import com.snapaie.android.data.ai.model.ModelRegistry
import com.snapaie.android.data.ai.model.ModelSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * The download engine: HTTPS + Range resume into a `.part` file, streamed to disk, then
 * size-checked, SHA-256 verified, and atomically moved into place.
 *
 * Nothing here touches Android lifecycle — it is driven by ModelDownloadWorker so the
 * same logic stays testable. Cancellation is cooperative: cancelling the calling
 * coroutine stops the transfer and leaves the `.part` file intact for resume.
 */
class ModelDownloader(
    private val registry: ModelRegistry,
    baseClient: OkHttpClient,
) {

    // Long transfers need generous socket timeouts; callTimeout stays unlimited.
    private val client: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Runs the full pipeline for [spec], reporting progress through [onState].
     * Returns the terminal state (COMPLETED or FAILED).
     */
    suspend fun execute(
        spec: ModelSpec,
        onState: (ModelDownloadState) -> Unit,
    ): ModelDownloadState = withContext(Dispatchers.IO) {
        val base = ModelDownloadState(
            modelId = spec.modelId,
            displayName = spec.displayName,
            totalBytes = spec.expectedBytes,
        )

        fun fail(error: ModelDownloadError, message: String, bytes: Long = 0L) = base.copy(
            status = ModelDownloadStatus.FAILED,
            downloadedBytes = bytes,
            error = error,
            errorMessage = message,
        )

        onState(base.copy(status = ModelDownloadStatus.CHECKING))

        // The manifest layer validates specs, but never trust an unchecked one here either.
        if (!spec.downloadUrl.startsWith("https://", ignoreCase = true)) {
            return@withContext fail(
                ModelDownloadError.NOT_CONFIGURED,
                "Model download URL must use HTTPS.",
            )
        }
        if (spec.expectedBytes <= 0L || !ModelManifestValidator.isValidSha256(spec.sha256)) {
            return@withContext fail(
                ModelDownloadError.NOT_CONFIGURED,
                "Model manifest is missing a valid size or SHA-256.",
            )
        }

        // Already installed and verified: nothing to do.
        if (registry.isInstalled(spec)) {
            return@withContext base.copy(
                status = ModelDownloadStatus.COMPLETED,
                downloadedBytes = spec.expectedBytes,
            )
        }

        registry.ensureDirFor(spec)
        val destination = registry.modelFile(spec)
        val part = registry.partFile(spec)

        onState(base.copy(status = ModelDownloadStatus.PREPARING, downloadedBytes = part.sizeOnDisk()))

        // A stale part file longer than the target can never be correct.
        if (part.sizeOnDisk() > spec.expectedBytes) part.delete()

        val needed = spec.expectedBytes - part.sizeOnDisk() + STORAGE_HEADROOM_BYTES
        if (registry.availableBytes() < needed) {
            val gb = String.format(java.util.Locale.US, "%.1f", needed / 1_000_000_000.0)
            return@withContext fail(
                ModelDownloadError.INSUFFICIENT_STORAGE,
                "Not enough free space. About $gb GB is required.",
                part.sizeOnDisk(),
            )
        }

        // Transfer, retrying transient network failures and resuming from the part file.
        var attempt = 0
        while (part.sizeOnDisk() < spec.expectedBytes) {
            coroutineContext.ensureActive()
            val failure = runCatching { transfer(spec, part, base, onState) }.exceptionOrNull()

            if (failure is CancellationException) throw failure
            if (failure is HttpFailure) {
                return@withContext fail(
                    ModelDownloadError.HTTP,
                    failure.message ?: "Download rejected by the server.",
                    part.sizeOnDisk(),
                )
            }
            if (failure is SizeMismatch) {
                part.delete()
                return@withContext fail(
                    ModelDownloadError.SIZE_MISMATCH,
                    failure.message ?: "Server file size does not match the configured model size.",
                )
            }
            if (failure != null && failure !is IOException) {
                return@withContext fail(
                    ModelDownloadError.UNKNOWN,
                    failure.message ?: "Download failed.",
                    part.sizeOnDisk(),
                )
            }

            if (part.sizeOnDisk() >= spec.expectedBytes) break

            // Either an IO error, or the stream ended early: back off and resume.
            attempt++
            if (attempt > MAX_NETWORK_ATTEMPTS) {
                return@withContext fail(
                    ModelDownloadError.NETWORK,
                    "Connection lost. Your progress is saved — resume when you are back online.",
                    part.sizeOnDisk(),
                )
            }
            onState(
                base.copy(
                    status = ModelDownloadStatus.PREPARING,
                    downloadedBytes = part.sizeOnDisk(),
                ),
            )
            delay(RETRY_DELAY_MS * attempt)
        }

        // Size gate before spending time hashing multiple GB.
        if (part.sizeOnDisk() != spec.expectedBytes) {
            part.delete()
            return@withContext fail(
                ModelDownloadError.SIZE_MISMATCH,
                "Downloaded ${part.sizeOnDisk()} bytes but expected ${spec.expectedBytes}.",
            )
        }

        onState(base.copy(status = ModelDownloadStatus.VERIFYING, downloadedBytes = spec.expectedBytes))
        val actualHash = runCatching { sha256(part) }.getOrElse {
            return@withContext fail(
                ModelDownloadError.STORAGE_IO,
                "Could not read the downloaded file to verify it.",
                part.sizeOnDisk(),
            )
        }
        if (!actualHash.equals(spec.expectedSha256, ignoreCase = true)) {
            // Never keep an artifact we cannot vouch for.
            part.delete()
            return@withContext fail(
                ModelDownloadError.CHECKSUM_MISMATCH,
                "Integrity check failed. The download was discarded; please try again.",
            )
        }

        onState(base.copy(status = ModelDownloadStatus.INSTALLING, downloadedBytes = spec.expectedBytes))
        // Same-directory rename: the model appears at its final path only once verified.
        destination.delete()
        val moved = part.renameTo(destination)
        if (!moved || !destination.isFile || destination.length() != spec.expectedBytes) {
            destination.delete()
            return@withContext fail(
                ModelDownloadError.STORAGE_IO,
                "Could not finalise the model file.",
                spec.expectedBytes,
            )
        }
        registry.markInstalled(spec)

        base.copy(status = ModelDownloadStatus.COMPLETED, downloadedBytes = spec.expectedBytes)
    }

    /** One HTTP attempt, appending to [part]. Throws on failure so the caller can retry. */
    private suspend fun transfer(
        spec: ModelSpec,
        part: File,
        base: ModelDownloadState,
        onState: (ModelDownloadState) -> Unit,
    ) {
        val already = part.sizeOnDisk()
        val builder = Request.Builder().url(spec.downloadUrl)
        if (already > 0L) builder.header("Range", "bytes=$already-")

        client.newCall(builder.build()).execute().use { response ->
            // 416 means the part file is at or past the end: drop it and start clean.
            if (response.code == HTTP_RANGE_NOT_SATISFIABLE) {
                part.delete()
                throw IOException("Resume offset rejected; restarting the download.")
            }
            if (!response.isSuccessful) {
                throw HttpFailure("Download failed with HTTP ${response.code}.")
            }

            // Server honoured the range only if it answered 206; otherwise rewrite from zero.
            val appending = response.code == HTTP_PARTIAL_CONTENT && already > 0L
            if (!appending && already > 0L) part.delete()

            val body = response.body ?: throw IOException("Empty response body.")
            val contentLength = body.contentLength()
            if (contentLength > 0L) {
                val serverTotal = if (appending) already + contentLength else contentLength
                if (serverTotal != spec.expectedBytes) {
                    throw SizeMismatch(
                        "Server reports $serverTotal bytes but the configured model is ${spec.expectedBytes} bytes.",
                    )
                }
            }

            var written = if (appending) already else 0L
            var lastEmitBytes = written
            var lastEmitAt = System.nanoTime()
            var speed = 0L

            body.byteStream().use { input ->
                FileOutputStream(part, appending).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        // Cooperative cancellation: pause/cancel stops here, part file kept.
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        written += read

                        // Throttled progress: counted in memory, emitted a few times a second.
                        val now = System.nanoTime()
                        val elapsedMs = (now - lastEmitAt) / 1_000_000L
                        if (elapsedMs >= PROGRESS_INTERVAL_MS) {
                            val deltaBytes = written - lastEmitBytes
                            if (elapsedMs > 0L) speed = deltaBytes * 1000L / elapsedMs
                            onState(
                                base.copy(
                                    status = ModelDownloadStatus.DOWNLOADING,
                                    downloadedBytes = written,
                                    bytesPerSecond = speed,
                                ),
                            )
                            lastEmitBytes = written
                            lastEmitAt = now
                        }
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            onState(
                base.copy(
                    status = ModelDownloadStatus.DOWNLOADING,
                    downloadedBytes = written,
                    bytesPerSecond = speed,
                ),
            )
        }
    }

    /** Streams the file through the digest; never loads it into memory. */
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun File.sizeOnDisk(): Long = if (isFile) length() else 0L

    private class HttpFailure(message: String) : IOException(message)

    private class SizeMismatch(message: String) : IOException(message)

    private companion object {
        const val BUFFER_BYTES = 1 shl 16 // 64 KB
        const val PROGRESS_INTERVAL_MS = 400L
        const val STORAGE_HEADROOM_BYTES = 350_000_000L
        const val MAX_NETWORK_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 2_000L
        const val HTTP_PARTIAL_CONTENT = 206
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
    }
}
