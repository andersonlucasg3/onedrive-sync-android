package com.anderson.onedrivesync.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.anderson.onedrivesync.R
import com.anderson.onedrivesync.ui.MainActivity

/**
 * Every notification the app posts.
 *
 * Channels are created once from [com.anderson.onedrivesync.App.onCreate]. Every `notify*` call is
 * a no-op when notifications are disabled or `POST_NOTIFICATIONS` was denied (API 33+), so callers
 * never need to check: a missing permission must never crash a sync.
 */
object Notifier {
    const val CHANNEL_PROGRESS = "sync_progress"
    const val CHANNEL_RESULT = "sync_result"
    const val CHANNEL_ALERTS = "sync_alerts"

    const val ID_PROGRESS = 1001
    const val ID_RESULT = 1002
    const val ID_ALERTS = 1003
    const val ID_UPDATE = 1004

    /** MainActivity extra the UI reads to open a specific screen. */
    const val EXTRA_SCREEN = "screen"
    const val SCREEN_CONFLICTS = "conflicts"
    const val SCREEN_PENDING = "pending"
    const val SCREEN_SETTINGS = "settings"

    private const val GROUP_ALERTS = "com.anderson.onedrivesync.ALERTS"
    private const val ICON = R.drawable.ic_notification

    private const val REQ_OPEN = 2000
    private const val REQ_CONFLICTS = 2001
    private const val REQ_PENDING = 2002
    private const val REQ_SETTINGS = 2003

    fun createChannels(context: Context) {
        val progress = NotificationChannel(
            CHANNEL_PROGRESS,
            context.getString(R.string.channel_progress_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.channel_progress_desc)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        val result = NotificationChannel(
            CHANNEL_RESULT,
            context.getString(R.string.channel_result_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.channel_result_desc)
        }
        // Conflicts and pending deletions need the user to act, so they alert (and badge) normally.
        val alerts = NotificationChannel(
            CHANNEL_ALERTS,
            context.getString(R.string.channel_alerts_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.channel_alerts_desc)
            setShowBadge(true)
        }
        NotificationManagerCompat.from(context).createNotificationChannels(listOf(progress, result, alerts))
    }

    /** Nothing queued: the notification is skipped instead of posting an empty message. */
    fun notifyPending(context: Context, pendingCount: Int, conflictCount: Int) {
        val text = pendingText(pendingCount, conflictCount)
        if (text.isEmpty() || !allowed(context)) return
        val screen = if (conflictCount > 0) SCREEN_CONFLICTS else SCREEN_PENDING
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(ICON)
            .setContentTitle(context.getString(R.string.notif_alerts_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(GROUP_ALERTS)
            .setGroupSummary(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, screen))
            .build()
        post(context, ID_ALERTS, notification)
    }

    fun notifyProgress(context: Context, text: String, done: Int, total: Int, cancelIntent: PendingIntent?) {
        if (!allowed(context)) return
        post(context, ID_PROGRESS, progressNotification(context, text, done, total, cancelIntent))
    }

    fun notifyError(context: Context, message: String) {
        if (!allowed(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(ICON)
            .setContentTitle(context.getString(R.string.notif_error_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, null))
            .build()
        post(context, ID_RESULT, notification)
    }

    fun cancelProgress(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_PROGRESS)
    }

    /** Daily update check found something: the download always starts from Settings. */
    fun notifyUpdate(context: Context, versionName: String) {
        if (!allowed(context)) return
        val text = context.getString(R.string.notif_update_text)
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(ICON)
            .setContentTitle(context.getString(R.string.notif_update_title, versionName))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, SCREEN_SETTINGS))
            .build()
        post(context, ID_UPDATE, notification)
    }

    /**
     * Also used for the foreground-service notification of [com.anderson.onedrivesync.work.SyncWorker]
     * and `SyncService`, so both share one notification id and the progress updates in place.
     */
    fun progressNotification(
        context: Context,
        text: String,
        done: Int,
        total: Int,
        cancelIntent: PendingIntent?,
    ): Notification {
        val max = total.coerceAtLeast(0)
        val value = if (max > 0) done.coerceIn(0, max) else 0
        val builder = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(ICON)
            .setContentTitle(context.getString(R.string.notif_progress_title))
            .setContentText(text)
            .setProgress(max, value, max == 0)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openApp(context, null))
        if (cancelIntent != null) {
            builder.addAction(
                NotificationCompat.Action.Builder(0, context.getString(R.string.notif_progress_cancel), cancelIntent).build(),
            )
        }
        return builder.build()
    }

    /**
     * Pure so it can be asserted on the JVM: the notification text is the only string that cannot
     * come from `strings.xml`, because building a [Notification] needs a `Context`.
     */
    fun pendingText(pending: Int, conflicts: Int): String {
        val deletions = deletionsText(pending)
        val conflictsLabel = conflictsText(conflicts)
        return when {
            pending > 0 && conflicts > 0 -> "$deletions e $conflictsLabel"
            conflicts > 0 -> conflictsLabel
            pending > 0 -> deletions
            else -> ""
        }
    }

    private fun deletionsText(count: Int): String =
        if (count == 1) "1 exclusão pendente" else "$count exclusões pendentes"

    private fun conflictsText(count: Int): String =
        if (count == 1) "1 conflito aguardando decisão" else "$count conflitos aguardando decisão"

    private fun allowed(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    /** Request codes are per screen because PendingIntent identity ignores the extras. */
    private fun openApp(context: Context, screen: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (screen != null) intent.putExtra(EXTRA_SCREEN, screen)
        val code = when (screen) {
            SCREEN_CONFLICTS -> REQ_CONFLICTS
            SCREEN_PENDING -> REQ_PENDING
            SCREEN_SETTINGS -> REQ_SETTINGS
            else -> REQ_OPEN
        }
        return PendingIntent.getActivity(
            context,
            code,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    @SuppressLint("MissingPermission") // guarded by allowed()
    private fun post(context: Context, id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // Permission revoked between the check and the post.
        }
    }
}
