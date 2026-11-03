package com.anderson.onedrivesync.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-arms the periodic sync after a reboot or an app update. No wake lock: WorkManager owns the
 * scheduling, and on HyperOS/MIUI the OS may still defer or block the run (see the onboarding).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Scheduler.ensurePeriodic(context)
        }
    }
}
