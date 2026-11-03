package dev.hermeskotlin.android

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the stack trace of a crash on disk, so it survives the process dying and can be sent to the host later.
 *
 * The posting itself does not happen here: writing the file is the part that has to work while the app is being
 * torn down. [pending] hands the files to whatever uploads them on the next start.
 */
object CrashLog {

    private const val DIR = "crashes"


    /** Installs the handler. Call once, as early as the process starts. */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Never let a logging failure swallow the crash the system still has to report.
            runCatching { write(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The crashes waiting to be sent, oldest first. */
    fun pending(context: Context): List<File> =
        dir(context)?.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            ?.sortedBy { it.name }
            .orEmpty()

    /**
     * Sends every pending crash through [send] (the host's route lives in core, where the client is), deleting
     * each one the host accepted. A failure leaves the file for next time; nothing is lost by trying again.
     */
    suspend fun sendAll(context: Context, send: suspend (String) -> Boolean): Int {
        var delivered = 0
        for (file in pending(context)) {
            val body = runCatching { file.readText() }.getOrNull() ?: continue
            if (send(body)) {
                sent(file)
                delivered++
            }
        }
        return delivered
    }

    /** Drops a crash once the host has it. */
    fun sent(file: File) {
        runCatching { file.delete() }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val dir = dir(context, create = true) ?: return
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val body = buildString {
            append("{\"version\":\"").append(versionName(context)).append("\",")
            append("\"device\":\"").append(escape("${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}"))
            append("\",\"thread\":\"").append(escape(thread.name)).append("\",")
            append("\"where\":\"").append(escape(error.stackTrace.firstOrNull()?.toString().orEmpty())).append("\",")
            append("\"message\":\"").append(escape(error.toString())).append("\",")
            append("\"stack\":\"").append(escape(error.stackTraceToString())).append("\",")
            append("\"non_fatal\":false}")
        }
        File(dir, "crash-$stamp.json").writeText(body)
    }

    private fun dir(context: Context, create: Boolean = false): File? {
        val dir = File(context.filesDir, DIR)
        if (create && !dir.exists()) dir.mkdirs()
        return dir.takeIf { it.isDirectory }
    }

    private fun versionName(context: Context): String =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        }.getOrDefault("")

    /** JSON without a parser: one line, no dependency, and nothing that can throw on a stack trace's quotes. */
    private fun escape(text: String): String = buildString {
        for (char in text) {
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char < ' ') append(' ') else append(char)
            }
        }
    }
}
