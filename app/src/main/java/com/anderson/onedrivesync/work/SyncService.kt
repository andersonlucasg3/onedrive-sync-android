package com.anderson.onedrivesync.work

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.anderson.onedrivesync.App
import com.anderson.onedrivesync.R
import com.anderson.onedrivesync.auth.AuthRequiredException
import com.anderson.onedrivesync.core.AppContainer
import com.anderson.onedrivesync.db.ConflictRow
import com.anderson.onedrivesync.db.PendingOp
import com.anderson.onedrivesync.notify.Notifier
import com.anderson.onedrivesync.sync.SyncEngine
import com.anderson.onedrivesync.sync.SyncState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Foreground (`dataSync`) sync for a user-triggered run, so a long first sync is not killed while
 * the app is in the background. The notification carries a "Cancelar" action that calls
 * [SyncEngine.cancel].
 */
class SyncService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val container: AppContainer get() = (application as App).container

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        // Started with startForegroundService: the service must be foreground within 5 s, whatever
        // the action, or the OS kills the process.
        startForeground(
            Notifier.ID_PROGRESS,
            Notifier.progressNotification(
                this,
                getString(R.string.notif_progress_starting),
                0,
                0,
                cancelIntent(),
            ),
        )
        if (action == ACTION_CANCEL) {
            container.syncEngine.cancel()
            stopSelf()
            return START_NOT_STICKY
        }
        if (job?.isActive != true) job = scope.launch { run(action, pairIdOf(intent)) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun run(action: String, pairId: Long?) {
        val engine = container.syncEngine
        val store = container.indexStore
        val allPairs = pairId == null || action == ACTION_SYNC_ALL
        store.log(
            System.currentTimeMillis(),
            "INFO",
            TAG,
            "início do serviço: ${if (allPairs) "todos os pares" else "par $pairId"}",
        )
        var outcome = "cancelado"
        try {
            coroutineScope {
                val progress = launch { engine.state.collect { publish(it) } }
                try {
                    if (allPairs) {
                        engine.syncAll(::onPending)
                    } else {
                        engine.syncPair(pairId, ::onPending)
                    }
                } finally {
                    progress.cancel()
                }
            }
            outcome = "concluído"
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthRequiredException) {
            outcome = "falhou: ${getString(R.string.notif_auth_required)}"
            Notifier.notifyError(this, getString(R.string.notif_auth_required))
        } catch (e: Exception) {
            outcome = "falhou: ${e.message ?: getString(R.string.notif_error_unknown)}"
            Notifier.notifyError(this, e.message ?: getString(R.string.notif_error_unknown))
        } finally {
            store.log(
                System.currentTimeMillis(),
                if (outcome.startsWith("falhou")) "WARN" else "INFO",
                TAG,
                "fim do serviço: $outcome",
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun publish(state: SyncState) {
        val running = state as? SyncState.Running ?: return
        val text = if (running.total > 0) running.step else getString(R.string.notif_progress_starting)
        startForeground(
            Notifier.ID_PROGRESS,
            Notifier.progressNotification(this, text, running.done, running.total, cancelIntent()),
        )
    }

    private fun onPending(pending: List<PendingOp>, conflicts: List<ConflictRow>) {
        Notifier.notifyPending(this, pending.size, conflicts.size)
    }

    private fun cancelIntent(): PendingIntent = PendingIntent.getService(
        this,
        REQ_CANCEL,
        Intent(this, SyncService::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun pairIdOf(intent: Intent?): Long? =
        intent?.getLongExtra(EXTRA_PAIR_ID, NO_PAIR_ID)?.takeIf { it > NO_PAIR_ID }

    companion object {
        const val ACTION_START = "com.anderson.onedrivesync.action.START"
        const val ACTION_CANCEL = "com.anderson.onedrivesync.action.CANCEL"
        const val ACTION_SYNC_ALL = "com.anderson.onedrivesync.action.SYNC_ALL"
        const val EXTRA_PAIR_ID = "pair_id"

        private const val NO_PAIR_ID = -1L
        private const val REQ_CANCEL = 3001
        private const val TAG = "SyncService"

        /** [pairId] null syncs every enabled pair. */
        fun start(context: Context, pairId: Long? = null) {
            val intent = Intent(context, SyncService::class.java).setAction(ACTION_START)
            if (pairId != null) intent.putExtra(EXTRA_PAIR_ID, pairId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SyncService::class.java).setAction(ACTION_CANCEL),
            )
        }
    }
}
