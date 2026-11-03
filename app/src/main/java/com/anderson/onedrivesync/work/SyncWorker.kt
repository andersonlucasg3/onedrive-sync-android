package com.anderson.onedrivesync.work

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.anderson.onedrivesync.App
import com.anderson.onedrivesync.R
import com.anderson.onedrivesync.auth.AuthRequiredException
import com.anderson.onedrivesync.core.AppContainer
import com.anderson.onedrivesync.db.ConflictRow
import com.anderson.onedrivesync.db.Pair
import com.anderson.onedrivesync.db.PendingOp
import com.anderson.onedrivesync.graph.GraphException
import com.anderson.onedrivesync.network.isOnWifi
import com.anderson.onedrivesync.notify.Notifier
import com.anderson.onedrivesync.sync.SyncEngine
import com.anderson.onedrivesync.sync.SyncState
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * One sync cycle as WorkManager work: periodic (see [Scheduler]) or ad-hoc.
 *
 * Runs the cycle in the foreground with the progress notification, because a first sync can be
 * long enough for the OS to kill the job. Sync failures that the engine already isolates per action
 * never reach here; the exceptions handled below abort the whole cycle.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val appContainer: AppContainer get() = (applicationContext as App).container

    override suspend fun doWork(): Result {
        val container = appContainer
        // Wi-Fi only: waiting for a Wi-Fi network is a normal outcome, not a failure to retry.
        if (container.prefs.wifionly && !isOnWifi(applicationContext)) {
            log(container, "ignorado: só Wi-Fi e a rede atual não é Wi-Fi")
            return Result.success()
        }
        if (!container.authManager.isSignedIn()) {
            log(container, "ignorado: sem sessão")
            return Result.success()
        }

        val pairId = inputData.getLong(KEY_PAIR_ID, NO_PAIR_ID).takeIf { it > NO_PAIR_ID }
        val pairs: List<Pair> = if (pairId != null) {
            listOfNotNull(container.indexStore.getPair(pairId))
        } else {
            container.indexStore.listPairs(enabledOnly = true)
        }
        if (pairs.isEmpty()) {
            log(container, "ignorado: nenhum par ativo")
            return Result.success()
        }

        val engine = container.syncEngine
        log(container, "início: ${pairs.size} par(es), tentativa ${runAttemptCount + 1}")
        goForeground()
        val result = try {
            coroutineScope {
                val progress = launch { engine.state.collect { publishProgress(it) } }
                try {
                    sync(engine, pairId)
                } finally {
                    progress.cancel()
                }
            }
            Result.success()
        } catch (e: CancellationException) {
            log(container, "cancelado")
            throw e
        } catch (e: AuthRequiredException) {
            Notifier.notifyError(applicationContext, applicationContext.getString(R.string.notif_auth_required))
            Result.success()
        } catch (e: IOException) {
            retryOrFail(e)
        } catch (e: GraphException) {
            if (e.isTransient()) retryOrFail(e) else failWith(e)
        } catch (e: Exception) {
            failWith(e)
        } finally {
            // WorkManager removes its own foreground notification when the work ends; this covers a
            // foreground promotion that was refused and left a plain progress notification behind.
            Notifier.cancelProgress(applicationContext)
        }
        log(container, "fim: $result")
        return result
    }

    /**
     * WorkManager asks for this on API < 31 to promote an expedited request to a foreground
     * service; the default implementation throws, so it has to be provided.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(applicationContext.getString(R.string.notif_progress_starting), 0, 0)

    private suspend fun sync(engine: SyncEngine, pairId: Long?) {
        val onNotify: (List<PendingOp>, List<ConflictRow>) -> Unit = { pending, conflicts ->
            Notifier.notifyPending(applicationContext, pending.size, conflicts.size)
        }
        if (pairId != null) engine.syncPair(pairId, onNotify) else engine.syncAll(onNotify)
    }

    private suspend fun goForeground() {
        try {
            setForeground(foregroundInfo(applicationContext.getString(R.string.notif_progress_starting), 0, 0))
        } catch (e: IllegalStateException) {
            // Android 12+ can refuse a foreground service for background work
            // (ForegroundServiceStartNotAllowedException). The sync still runs, only without the
            // foreground priority.
            appContainer.indexStore.log(
                System.currentTimeMillis(),
                "WARN",
                TAG,
                "notificação de progresso indisponível: ${e.message}",
            )
        }
    }

    private fun foregroundInfo(text: String, done: Int, total: Int): ForegroundInfo = ForegroundInfo(
        Notifier.ID_PROGRESS,
        Notifier.progressNotification(applicationContext, text, done, total, null),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    /** The engine reports one step per action, so `done`/`total` are already coarse enough to post. */
    private suspend fun publishProgress(state: SyncState) {
        val running = state as? SyncState.Running ?: return
        if (running.total <= 0) return
        Notifier.notifyProgress(
            context = applicationContext,
            text = running.step,
            done = running.done,
            total = running.total,
            cancelIntent = null,
        )
        setProgress(workDataOf(KEY_DONE to running.done, KEY_TOTAL to running.total, KEY_STEP to running.step))
    }

    private fun retryOrFail(e: Exception): Result =
        if (runAttemptCount < MAX_RUN_ATTEMPTS) Result.retry() else failWith(e)

    private fun failWith(e: Exception): Result {
        Notifier.notifyError(applicationContext, e.message ?: applicationContext.getString(R.string.notif_error_unknown))
        return Result.failure()
    }

    private fun log(container: AppContainer, message: String) =
        container.indexStore.log(System.currentTimeMillis(), "INFO", TAG, message)

    companion object {
        /** Optional input: sync a single pair instead of every enabled one. */
        const val KEY_PAIR_ID = "pair_id"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_STEP = "step"

        private const val NO_PAIR_ID = -1L
        private const val MAX_RUN_ATTEMPTS = 3
        private const val TAG = "SyncWorker"
    }
}

/** 429 and 5xx are worth another attempt; anything else (401, 403, 410, 4xx) is not. */
private fun GraphException.isTransient(): Boolean = status == 429 || status in 500..599
