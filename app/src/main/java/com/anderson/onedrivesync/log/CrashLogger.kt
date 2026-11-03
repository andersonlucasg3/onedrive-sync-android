package com.anderson.onedrivesync.log

import android.content.Context
import android.os.Build
import com.anderson.onedrivesync.App
import com.anderson.onedrivesync.BuildConfig
import com.anderson.onedrivesync.db.LogDao
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Last-resort diagnostics for a build deployed without ADB: an uncaught exception is written to
 * `filesDir/crash/` before the process dies, then imported into the persisted log on the next start.
 *
 * The file is the only channel left when the database itself is what failed, so writing it never
 * depends on the database, and the previous handler always runs: the crash is never swallowed.
 */
object CrashLogger {
    private const val DIR = "crash"
    private const val MAX_MESSAGE_CHARS = 8000
    private const val MAX_MESSAGE_LINES = 80
    private const val TAG = "Crash"

    /** Installs the handler, chaining to whatever handler was installed before. */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writePending(appContext, thread, throwable)
            } catch (_: Throwable) {
                // A failing logger must never replace the crash it is reporting.
            }
            if (previous != null) previous.uncaughtException(thread, throwable) else throw throwable
        }
    }

    /** Moves every pending crash file into the log; called from `App.onCreate`. */
    fun importPending(context: Context) {
        try {
            val app = context.applicationContext as? App ?: return
            val database = app.container.db.writableDatabase
            for (file in pendingFiles(context)) {
                try {
                    LogDao.insert(database, System.currentTimeMillis(), "ERROR", TAG, crashMessage(file.readText()))
                    file.delete()
                } catch (_: Exception) {
                    // The database may be the crash cause itself: keep the file for the error screen.
                }
            }
        } catch (_: Exception) {
            // Import runs in onCreate: a database failure must not stop the app from opening.
        }
    }

    /** Every file in `filesDir/crash`, oldest first; the startup error screen shares these when the DB is down. */
    fun pendingFiles(context: Context): List<File> =
        File(context.filesDir, DIR).listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()

    private fun writePending(context: Context, thread: Thread, throwable: Throwable) {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val file = File(dir, "pending-${System.currentTimeMillis()}.txt")
        val stack = StringWriter().also { writer ->
            PrintWriter(writer).use { throwable.printStackTrace(it) }
        }.toString()
        val text = buildString {
            appendLine("Quando: ${System.currentTimeMillis()}")
            appendLine("Thread: ${thread.name}")
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Aparelho: ${Build.MANUFACTURER} ${Build.MODEL} · SDK ${Build.VERSION.SDK_INT}")
            appendLine("Exceção: ${throwable.javaClass.name}: ${throwable.message.orEmpty()}")
            appendLine()
            append(stack)
        }
        // writeText opens and closes the stream, so the file is flushed before the process dies.
        file.writeText(text)
    }

    /** First lines only: the log row is a message, not a file dump. */
    private fun crashMessage(text: String): String =
        text.lineSequence().take(MAX_MESSAGE_LINES).joinToString("\n").take(MAX_MESSAGE_CHARS)
}
