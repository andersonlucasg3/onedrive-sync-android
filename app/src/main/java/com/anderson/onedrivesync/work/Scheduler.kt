package com.anderson.onedrivesync.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.anderson.onedrivesync.core.Prefs
import java.util.concurrent.TimeUnit

/** Enqueues the sync work: one periodic request plus one ad-hoc request triggered by the user. */
object Scheduler {
    const val PERIODIC_WORK_NAME = "sync_periodic"
    const val NOW_WORK_NAME = "sync_now"
    const val UPDATE_WORK_NAME = "update_check"

    const val MIN_INTERVAL_MINUTES = 15L
    const val MAX_INTERVAL_MINUTES = 1440L

    private const val BACKOFF_SECONDS = 30L

    /**
     * WorkManager's floor for periodic work is 15 minutes and more than a day makes no sense, so the
     * free-text setting is clamped instead of rejected. Unit is minutes.
     */
    fun clampInterval(minutes: Int): Long =
        minutes.toLong().coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)

    fun ensurePeriodic(context: Context) {
        val minutes = clampInterval(Prefs(context).syncIntervalMinutes)
        val request = PeriodicWorkRequestBuilder<SyncWorker>(minutes, TimeUnit.MINUTES)
            .setConstraints(networkConstraint())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        // UPDATE keeps the existing request id alive and only reschedules, which is what a settings
        // change or a reboot should do.
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Once a day: KEEP, so upgrading the app does not postpone the next check again. */
    fun ensureUpdateCheck(context: Context) {
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS)
            .setConstraints(networkConstraint())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UPDATE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun runNow(context: Context, pairId: Long? = null) {
        val builder = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(networkConstraint())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        if (pairId != null) builder.setInputData(workDataOf(SyncWorker.KEY_PAIR_ID to pairId))
        WorkManager.getInstance(context)
            .enqueueUniqueWork(NOW_WORK_NAME, ExistingWorkPolicy.KEEP, builder.build())
    }

    fun cancelAll(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
        workManager.cancelUniqueWork(NOW_WORK_NAME)
    }

    private fun networkConstraint(): Constraints =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
}
