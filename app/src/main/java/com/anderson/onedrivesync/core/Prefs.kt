package com.anderson.onedrivesync.core

import android.content.Context

internal const val PREFS_FILE = "odsync"

class Prefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    var clientId: String?
        get() = prefs.getString(KEY_CLIENT_ID, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_CLIENT_ID, value).apply()

    var wifionly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, false)
        set(value) = prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()

    var syncIntervalMinutes: Int
        get() = prefs.getInt(KEY_SYNC_INTERVAL, DEFAULT_SYNC_INTERVAL_MINUTES)
        set(value) = prefs.edit().putInt(KEY_SYNC_INTERVAL, value).apply()

    private companion object {
        const val KEY_CLIENT_ID = "client_id"
        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_SYNC_INTERVAL = "sync_interval_minutes"
        const val DEFAULT_SYNC_INTERVAL_MINUTES = 30
    }
}
