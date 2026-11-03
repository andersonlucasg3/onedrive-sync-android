package com.anderson.onedrivesync

import android.app.Application
import android.os.Build
import com.anderson.onedrivesync.core.AppContainer
import com.anderson.onedrivesync.db.LogDao
import com.anderson.onedrivesync.log.CrashLogger
import com.anderson.onedrivesync.notify.Notifier
import com.anderson.onedrivesync.work.Scheduler

class App : Application() {
    lateinit var container: AppContainer
        private set

    val appContainer: AppContainer get() = container

    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
        container = AppContainer(this)
        CrashLogger.importPending(this)
        log("INFO", "start v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) sdk=${Build.VERSION.SDK_INT} ${Build.MANUFACTURER} ${Build.MODEL}")
        // Startup work is best effort: a failure here must never keep the app from opening.
        try {
            Notifier.createChannels(this)
        } catch (e: Exception) {
            log("ERROR", "falha ao criar os canais de notificação: $e")
        }
        try {
            Scheduler.ensurePeriodic(this)
        } catch (e: Exception) {
            log("ERROR", "falha ao agendar a sincronização periódica: $e")
        }
        try {
            Scheduler.ensureUpdateCheck(this)
        } catch (e: Exception) {
            log("ERROR", "falha ao agendar a verificação de atualizações: $e")
        }
    }

    /** Best effort: the crash files still carry diagnostics when the database cannot be opened. */
    private fun log(level: String, message: String) {
        try {
            LogDao.insert(container.db.writableDatabase, System.currentTimeMillis(), level, TAG, message)
        } catch (_: Exception) {
            // Nothing else to do: the app must keep starting.
        }
    }

    private companion object {
        const val TAG = "App"
    }
}
