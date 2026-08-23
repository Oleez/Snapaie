package com.snapaie.android.domain.condense

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.snapaie.android.MainActivity
import com.snapaie.android.R
import com.snapaie.android.SnapAieApplication
import com.snapaie.android.data.model.CondenseJobState
import kotlinx.coroutines.delay

/**
 * Runs a whole-book condensation as long-running foreground work.
 *
 * A 500-page book is roughly 150k words in and 50k out, which on a 2B model running
 * on-device is hours, not minutes. That shapes everything here: the job has to survive
 * backgrounding, process death and a reboot, and it has to be honest with the user about
 * how long it will take rather than showing an indeterminate spinner all night.
 *
 * Resume is free — [CondensePipeline] asks the database for the next unfinished beat, so
 * a restart continues exactly where it stopped with no separate recovery path.
 */
class BookCondenseWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val container by lazy { (applicationContext as SnapAieApplication).container }
    private val bookId by lazy { inputData.getLong(KEY_BOOK_ID, -1L) }

    override suspend fun doWork(): Result {
        if (bookId <= 0L) return Result.failure()
        val repository = container.bookRepository
        val book = container.database.bookDao().getBook(bookId) ?: return Result.failure()

        setForegroundSafely(book.title, 0, 0, null)

        val started = System.currentTimeMillis()
        var lastNotifiedAt = 0L

        val outcome = runCatching {
            container.condensePipeline.run(bookId) { progress ->
                waitOutThermalThrottle(book.title, progress)

                val now = System.currentTimeMillis()
                if (now - lastNotifiedAt >= NOTIFY_INTERVAL_MS || progress.beatsDone == progress.beatsTotal) {
                    lastNotifiedAt = now
                    setForegroundSafely(
                        book.title,
                        progress.beatsDone,
                        progress.beatsTotal,
                        estimateRemainingMs(started, progress),
                    )
                }
                setProgress(
                    workDataOf(
                        KEY_PROGRESS_DONE to progress.beatsDone,
                        KEY_PROGRESS_TOTAL to progress.beatsTotal,
                        KEY_PROGRESS_WORDS to progress.producedWords,
                    ),
                )
            }
        }.getOrElse { error ->
            if (isStopped) {
                // Cancelled or pre-empted: leave the job resumable rather than failed.
                repository.latestJob(bookId)?.let { repository.setJobState(it.id, CondenseJobState.PAUSED) }
                return Result.success()
            }
            CondenseOutcome.Failed(error.message ?: "The run stopped unexpectedly.")
        }

        return when (outcome) {
            is CondenseOutcome.Completed -> {
                showTerminalNotification(
                    "\"${book.title}\" is ready",
                    "Condensed to about ${outcome.producedWords} words. Open it to read or export.",
                )
                Result.success()
            }

            is CondenseOutcome.LadderPassReady -> {
                // The intermediate rung finished. Re-enqueue for the next one rather than
                // looping here, so the framework keeps its grip on a job this long.
                enqueue(applicationContext, bookId, chargingOnly = inputData.getBoolean(KEY_CHARGING_ONLY, true))
                Result.success()
            }

            is CondenseOutcome.NothingToDo -> Result.success()

            is CondenseOutcome.Failed -> {
                repository.latestJob(bookId)?.let { job ->
                    repository.updateJob(
                        job.copy(state = CondenseJobState.FAILED.name, errorMessage = outcome.message),
                    )
                }
                showTerminalNotification("\"${book.title}\" stopped", outcome.message)
                Result.failure()
            }
        }
    }

    /**
     * Holds the run while the phone is too hot to continue.
     *
     * Hours of sustained GPU inference *will* trip thermal throttling. Without this the
     * run does not stop — it just gets slower and slower while the device cooks, which is
     * both worse for the hardware and invisible to the user. Pausing at SEVERE and saying
     * so is the honest behaviour.
     */
    private suspend fun waitOutThermalThrottle(title: String, progress: CondenseProgress) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val power = applicationContext.getSystemService(PowerManager::class.java) ?: return

        var waited = 0L
        while (!isStopped && power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
            if (waited == 0L) {
                setForegroundSafely(title, progress.beatsDone, progress.beatsTotal, null, cooling = true)
            }
            delay(THERMAL_POLL_MS)
            waited += THERMAL_POLL_MS
            if (waited >= THERMAL_MAX_WAIT_MS) return
        }
        if (waited > 0L) {
            setForegroundSafely(title, progress.beatsDone, progress.beatsTotal, null)
        }
    }

    /**
     * Time left, from a rolling average of how long the finished beats actually took.
     * Beats vary, but over hundreds of them the mean is a far better estimate than any
     * fixed per-beat guess, and it adapts when the device throttles.
     */
    private fun estimateRemainingMs(startedAtMillis: Long, progress: CondenseProgress): Long? {
        if (progress.beatsDone <= 0) return null
        val elapsed = System.currentTimeMillis() - startedAtMillis
        val perBeat = elapsed / progress.beatsDone
        val remaining = (progress.beatsTotal - progress.beatsDone).coerceAtLeast(0)
        return perBeat * remaining
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        buildForegroundInfo("Condensing", 0, 0, null, cooling = false)

    private suspend fun setForegroundSafely(
        title: String,
        done: Int,
        total: Int,
        remainingMs: Long?,
        cooling: Boolean = false,
    ) {
        // A missing notification permission must not kill a four-hour job.
        runCatching { setForeground(buildForegroundInfo(title, done, total, remainingMs, cooling)) }
    }

    private fun buildForegroundInfo(
        title: String,
        done: Int,
        total: Int,
        remainingMs: Long?,
        cooling: Boolean,
    ): ForegroundInfo {
        ensureChannel(applicationContext)
        val body = when {
            cooling -> "Paused — the device is too warm. Resuming automatically."
            total <= 0 -> "Preparing…"
            else -> buildString {
                append("Passage $done of $total")
                remainingMs?.let { append(" · ${formatDuration(it)} left") }
            }
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Condensing \"$title\"")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setProgress(total.coerceAtLeast(1), done, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent(applicationContext))
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
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
            .build()
        runCatching { manager.notify(TERMINAL_NOTIFICATION_ID, notification) }
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Book condensing", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress while a book is being condensed on this device."
                setShowBadge(false)
            },
        )
    }

    private fun formatDuration(millis: Long): String {
        val minutes = millis / 60_000
        return when {
            minutes >= 120 -> "${minutes / 60} hr"
            minutes >= 60 -> "${minutes / 60} hr ${minutes % 60} min"
            minutes >= 1 -> "$minutes min"
            else -> "under a minute"
        }
    }

    companion object {
        const val KEY_BOOK_ID = "bookId"
        const val KEY_CHARGING_ONLY = "chargingOnly"
        const val KEY_PROGRESS_DONE = "done"
        const val KEY_PROGRESS_TOTAL = "total"
        const val KEY_PROGRESS_WORDS = "words"

        private const val CHANNEL_ID = "snapaie_condense"
        private const val NOTIFICATION_ID = 4301
        private const val TERMINAL_NOTIFICATION_ID = 4302
        private const val NOTIFY_INTERVAL_MS = 4_000L
        private const val THERMAL_POLL_MS = 30_000L
        private const val THERMAL_MAX_WAIT_MS = 30L * 60L * 1000L

        fun workName(bookId: Long): String = "snapaie-condense-$bookId"

        /**
         * Unique work per book, KEEP so a second tap joins the run in progress instead of
         * restarting a job that may already be hours in.
         */
        fun enqueue(context: Context, bookId: Long, chargingOnly: Boolean) {
            val request = OneTimeWorkRequestBuilder<BookCondenseWorker>()
                .setInputData(
                    workDataOf(
                        KEY_BOOK_ID to bookId,
                        KEY_CHARGING_ONLY to chargingOnly,
                    ),
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresCharging(chargingOnly)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(workName(bookId), ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context, bookId: Long) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(bookId))
        }

        const val TAG = "snapaie-condense"
    }
}
