package com.snapaie.android.data.ai.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.snapaie.android.MainActivity
import com.snapaie.android.R
import com.snapaie.android.SnapAieApplication
import com.snapaie.android.data.ai.model.ModelBackend
import com.snapaie.android.data.ai.model.ModelSpec

/**
 * Runs the model download as long-running background work with a foreground service and
 * a progress notification.
 *
 * WorkManager was chosen over a bare foreground service because a multi-GB transfer must
 * survive process death, not just backgrounding: unique work prevents duplicates, the
 * work record outlives the process, and stopping for connectivity or Doze is handled by
 * the framework rather than by us. The `.part` file makes every restart a resume.
 */
class ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val container by lazy { (applicationContext as SnapAieApplication).container }

    private fun specFromInput(): ModelSpec? {
        val modelId = inputData.getString(KEY_MODEL_ID).orEmpty()
        val version = inputData.getString(KEY_VERSION).orEmpty()
        val fileName = inputData.getString(KEY_FILENAME).orEmpty()
        val url = inputData.getString(KEY_URL).orEmpty()
        val size = inputData.getLong(KEY_SIZE, 0L)
        val sha = inputData.getString(KEY_SHA256).orEmpty()
        if (modelId.isBlank() || version.isBlank() || fileName.isBlank() || url.isBlank() || size <= 0L) {
            return null
        }
        return ModelSpec(
            modelId = modelId,
            version = version,
            fileName = fileName,
            downloadUrl = url,
            expectedBytes = size,
            sha256 = sha,
            runtime = inputData.getString(KEY_RUNTIME).orEmpty(),
            runtimeVersion = inputData.getInt(KEY_RUNTIME_VERSION, 1),
            backend = ModelBackend.fromWire(inputData.getString(KEY_BACKEND)),
        )
    }

    override suspend fun doWork(): Result {
        val spec = specFromInput() ?: return Result.failure()
        val controller = container.modelDownloadController

        setForegroundSafely(spec.displayName, 0, 0L, 0L)

        val terminal = try {
            controller.run(spec) { progress ->
                // Notification updates are already throttled by the downloader's emit rate.
                notify(spec.displayName, progress)
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            // User paused or cancelled: the part file decides whether this can resume.
            controller.onWorkerStopped(spec)
            throw cancellation
        }

        return when (terminal.status) {
            ModelDownloadStatus.COMPLETED -> {
                showTerminalNotification(
                    title = "Ready to go",
                    text = "Offline AI is installed. snapaie now works without internet.",
                )
                Result.success()
            }
            ModelDownloadStatus.FAILED -> when (terminal.error) {
                // Transient: let WorkManager retry with backoff; the part file is kept.
                ModelDownloadError.NETWORK -> Result.retry()
                else -> {
                    showTerminalNotification(
                        title = "Download didn't finish",
                        text = terminal.errorMessage ?: "It stopped partway. Open snapaie to try again.",
                    )
                    Result.failure()
                }
            }
            else -> Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        buildForegroundInfo(
            inputData.getString(KEY_MODEL_ID).orEmpty(),
            0,
            0L,
            inputData.getLong(KEY_SIZE, 0L),
        )

    private suspend fun setForegroundSafely(name: String, percent: Int, done: Long, total: Long) {
        // Missing notification permission must not kill the download.
        runCatching { setForeground(buildForegroundInfo(name, percent, done, total)) }
    }

    private fun buildForegroundInfo(name: String, percent: Int, done: Long, total: Long): ForegroundInfo {
        ensureChannel(applicationContext)
        val notification = buildNotification(applicationContext, name, percent, done, total)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun notify(name: String, progress: ModelDownloadState) {
        ensureChannel(applicationContext)
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        runCatching {
            manager.notify(
                NOTIFICATION_ID,
                buildNotification(
                    applicationContext,
                    name,
                    progress.percent,
                    progress.downloadedBytes,
                    progress.totalBytes,
                    statusLabel(progress),
                ),
            )
        }
    }

    private fun showTerminalNotification(title: String, text: String) {
        ensureChannel(applicationContext)
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent(applicationContext))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { manager.notify(TERMINAL_NOTIFICATION_ID, notification) }
    }

    private fun statusLabel(progress: ModelDownloadState): String = when (progress.status) {
        ModelDownloadStatus.CHECKING -> "Checking…"
        ModelDownloadStatus.PREPARING -> "Preparing…"
        ModelDownloadStatus.VERIFYING -> "Verifying integrity…"
        ModelDownloadStatus.INSTALLING -> "Installing…"
        else -> {
            val speed = progress.bytesPerSecond
            val eta = progress.etaSeconds
            buildString {
                append(formatBytes(progress.downloadedBytes))
                append(" / ")
                append(formatBytes(progress.totalBytes))
                if (speed > 0L) append(" · ${formatBytes(speed)}/s")
                if (eta != null && eta > 0L) append(" · ${formatDuration(eta)} left")
            }
        }
    }

    companion object {
        const val KEY_MODEL_ID = "modelId"
        const val KEY_VERSION = "version"
        const val KEY_FILENAME = "filename"
        const val KEY_URL = "url"
        const val KEY_SIZE = "sizeBytes"
        const val KEY_SHA256 = "sha256"
        const val KEY_RUNTIME = "runtime"
        const val KEY_RUNTIME_VERSION = "runtimeVersion"
        const val KEY_BACKEND = "backend"

        const val CHANNEL_ID = "snapaie_model_download"
        const val NOTIFICATION_ID = 4201
        const val TERMINAL_NOTIFICATION_ID = 4202

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Model download",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "Progress for the offline AI model download" },
            )
        }

        fun buildNotification(
            context: Context,
            name: String,
            percent: Int,
            done: Long,
            total: Long,
            status: String? = null,
        ) = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Downloading offline AI")
            .setContentText(status ?: "${formatBytes(done)} / ${formatBytes(total)}")
            .setSubText("$percent%")
            .setProgress(100, percent.coerceIn(0, 100), total <= 0L)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent(context))
            .addAction(
                0,
                "Pause",
                ModelDownloadActionReceiver.pendingIntent(
                    context,
                    ModelDownloadActionReceiver.ACTION_PAUSE,
                    requestCode = 1,
                ),
            )
            .addAction(
                0,
                "Cancel",
                ModelDownloadActionReceiver.pendingIntent(
                    context,
                    ModelDownloadActionReceiver.ACTION_CANCEL,
                    requestCode = 2,
                ),
            )
            .build()

        private fun openAppIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )

        fun formatBytes(bytes: Long): String = when {
            bytes >= 1_000_000_000L -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
            bytes >= 1_000_000L -> String.format(java.util.Locale.US, "%.0f MB", bytes / 1_000_000.0)
            bytes >= 1_000L -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1_000.0)
            else -> "$bytes B"
        }

        fun formatDuration(seconds: Long): String = when {
            seconds >= 3600 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
            seconds >= 60 -> "${seconds / 60}m"
            else -> "${seconds}s"
        }
    }
}
