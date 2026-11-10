package io.github.lrosa2.singletrail.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small rolling app log in `filesDir/logs/` (spec §11). It never contains coordinates (§9.2):
 * only ride ids, states, reasons and stack traces.
 */
class AppLog(filesDir: File) {
    private val dir = File(filesDir, "logs")
    private val file = File(dir, "app.log")
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)

    @Synchronized
    fun log(tag: String, message: String) {
        Log.i(tag, message)
        try {
            dir.mkdirs()
            if (file.length() > MAX_BYTES) file.renameTo(File(dir, "app.log.1"))
            file.appendText("${stamp.format(Date())} $tag: $message\n")
        } catch (_: Exception) {
            // Logging must never break the app.
        }
    }

    /** Writes uncaught exceptions to `logs/crash-<time>.txt`, then lets the platform handle them. */
    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                dir.mkdirs()
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
                File(dir, "crash-${System.currentTimeMillis()}.txt").writeText("Thread ${thread.name}\n$trace")
            } catch (_: Exception) {
                // Nothing more can be done.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Logs why the process ended since the last start (spec §6), once each. */
    fun recordExitReasons(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val prefs = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
        val seen = prefs.getLong(LAST_EXIT_SEEN, 0L)
        val reasons = context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(context.packageName, 0, 5)
            .filter { it.timestamp > seen }
            .sortedBy { it.timestamp }
        for (info in reasons) {
            log("Exit", "${stamp.format(Date(info.timestamp))} reason=${info.reason} importance=${info.importance} ${info.description.orEmpty()}")
        }
        reasons.lastOrNull()?.let { last -> prefs.edit { putLong(LAST_EXIT_SEEN, last.timestamp) } }
    }

    private companion object {
        const val MAX_BYTES = 256 * 1024L
        const val LAST_EXIT_SEEN = "last_exit_seen"
    }
}
