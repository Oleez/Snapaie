package com.snapaie.android.core.diagnostics

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records the last few crashes to a file the user can send on.
 *
 * On-device AI fails in ways a stack trace alone does not explain — how much RAM the phone
 * has, whether the model was loaded, which backend it was on. Without that, a report is
 * "it keeps crashing" and the only way to find the cause is to guess. This keeps just
 * enough context, and only on this device.
 */
object CrashLog {

    private const val FILE = "crash-log.txt"
    private const val MAX_ENTRIES = 5
    private const val SEPARATOR = "\n===== =====\n"

    @Volatile
    private var context: Context? = null

    @Volatile
    private var note: String = ""

    /** Installs the handler. Any existing one is still called, so the OS dialog still shows. */
    fun install(appContext: Context) {
        context = appContext.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(error, thread.name) }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * A breadcrumb for what the app was doing, written into the next crash entry.
     * Deliberately coarse — "writing assistant", "condensing" — never user content.
     */
    fun breadcrumb(what: String) {
        note = what
    }

    fun file(): File? = context?.let { File(it.filesDir, FILE) }

    fun read(): String = file()?.takeIf { it.isFile }?.runCatching { readText() }?.getOrNull().orEmpty()

    fun clear() {
        runCatching { file()?.delete() }
    }

    private fun record(error: Throwable, threadName: String) {
        val target = file() ?: return
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val runtime = Runtime.getRuntime()

        val entry = buildString {
            appendLine(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            appendLine("doing: ${note.ifBlank { "unknown" }}")
            appendLine("thread: $threadName")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
            appendLine("abi: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine(
                "heap: ${(runtime.totalMemory() - runtime.freeMemory()) / 1_000_000} MB used " +
                    "of ${runtime.maxMemory() / 1_000_000} MB max",
            )
            appendLine(stack.take(6_000))
        }

        // Keep the most recent few; an unbounded log is one more thing to go wrong.
        val existing = runCatching { if (target.isFile) target.readText() else "" }.getOrDefault("")
        val kept = (listOf(entry) + existing.split(SEPARATOR).filter { it.isNotBlank() })
            .take(MAX_ENTRIES)
        runCatching { target.writeText(kept.joinToString(SEPARATOR)) }
    }
}
