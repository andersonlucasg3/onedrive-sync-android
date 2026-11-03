package com.anderson.onedrivesync.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.anderson.onedrivesync.App
import com.anderson.onedrivesync.core.AppContainer
import com.anderson.onedrivesync.notify.Notifier
import com.anderson.onedrivesync.update.isNewerVersion

/**
 * Daily GitHub Releases check. Never downloads on its own: it only posts a notification, and the
 * user starts the download from Settings.
 */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val appContainer: AppContainer get() = (applicationContext as App).container

    override suspend fun doWork(): Result {
        val container = appContainer
        val checker = container.updateChecker
        val release = checker.latest()
        if (release == null) {
            log(container, "nenhuma release encontrada")
            return Result.success()
        }
        if (!isNewerVersion(checker.currentVersion, release.versionName)) {
            log(container, "versão ${release.versionName} não é mais nova que ${checker.currentVersion}")
            return Result.success()
        }
        log(container, "atualização disponível: ${release.versionName}")
        Notifier.notifyUpdate(applicationContext, release.versionName)
        return Result.success()
    }

    private fun log(container: AppContainer, message: String) =
        container.indexStore.log(System.currentTimeMillis(), "INFO", TAG, message)

    private companion object {
        const val TAG = "UpdateWorker"
    }
}
