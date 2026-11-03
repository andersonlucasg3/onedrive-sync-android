package com.anderson.onedrivesync.hyperos

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Permission checklist and the intent jumps the onboarding uses on HyperOS/MIUI.
 *
 * Android exposes no public API for the MIUI autostart switch, so [Checklist.autostart] is only an
 * approximation: `true` everywhere it cannot be checked (any non-MIUI device) and `true` on MIUI
 * only while the security center screen can still be opened. The user confirms on that screen.
 */
object HyperOsPermissions {

    data class Checklist(
        val allFiles: Boolean,
        val notifications: Boolean,
        val battery: Boolean,
        val autostart: Boolean,
    )

    private val MIUI_MANUFACTURERS = setOf("xiaomi", "redmi", "poco")

    fun checklist(context: Context): Checklist = Checklist(
        allFiles = Environment.isExternalStorageManager(),
        notifications = notificationsGranted(context),
        battery = isIgnoringBatteryOptimizations(context),
        autostart = autostartSatisfied(context),
    )

    /** Manufacturer or the MIUI system property; the latter needs reflection (no public API). */
    fun isMiui(): Boolean {
        if (Build.MANUFACTURER.lowercase(Locale.ROOT) in MIUI_MANUFACTURERS) return true
        return !systemProperty("ro.miui.ui.version.name").isNullOrEmpty()
    }

    fun openAllFilesAccess(context: Context) {
        if (open(context, appSettingsIntent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, context))) return
        if (open(context, Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))) return
        openAppDetails(context)
    }

    fun openNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        if (open(context, intent)) return
        openAppDetails(context)
    }

    fun requestIgnoreBatteryOptimizations(context: Context) {
        val direct = appSettingsIntent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, context)
        if (open(context, direct)) return
        if (open(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) return
        openAppDetails(context)
    }

    fun openAutostartSettings(context: Context) {
        if (open(context, autostartIntent())) return
        if (open(context, appDetailsIntent(context))) return
        open(context, Intent(Settings.ACTION_SETTINGS))
    }

    fun openAppDetails(context: Context) {
        if (open(context, appDetailsIntent(context))) return
        open(context, Intent(Settings.ACTION_SETTINGS))
    }

    private fun notificationsGranted(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return false
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** MIUI hides the switch, so the best available answer is "the screen still exists". */
    private fun autostartSatisfied(context: Context): Boolean {
        if (!isMiui()) return true
        // Flags 0: the intent is explicit, so MATCH_DEFAULT_ONLY would filter by category.
        return context.packageManager.resolveActivity(autostartIntent(), 0) != null
    }

    private fun autostartIntent(): Intent = Intent()
        .setComponent(ComponentName(MIUI_SECURITY_CENTER, MIUI_AUTOSTART_ACTIVITY))

    private fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

    private fun appSettingsIntent(action: String, context: Context): Intent =
        Intent(action, Uri.fromParts("package", context.packageName, null))

    private fun open(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    private fun systemProperty(key: String): String? = try {
        val systemProperties = Class.forName("android.os.SystemProperties")
        systemProperties.getMethod("get", String::class.java).invoke(null, key) as? String
    } catch (e: ReflectiveOperationException) {
        null
    } catch (e: SecurityException) {
        null
    }

    private const val MIUI_SECURITY_CENTER = "com.miui.securitycenter"
    private const val MIUI_AUTOSTART_ACTIVITY = "com.miui.permcenter.autostart.AutoStartManagementActivity"
}
