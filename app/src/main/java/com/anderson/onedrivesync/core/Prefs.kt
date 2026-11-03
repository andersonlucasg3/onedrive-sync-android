package com.anderson.onedrivesync.core

import android.content.Context

internal const val PREFS_FILE = "odsync"
internal const val DEFAULT_TENANT = "consumers"

/** Tenant is a single URL segment: no bars or spaces, and a blank value means "consumers". */
internal fun normalizeTenant(value: String): String =
    value.replace("/", "").filterNot { it.isWhitespace() }.ifBlank { DEFAULT_TENANT }

class Prefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    var tenant: String
        get() = normalizeTenant(prefs.getString(KEY_TENANT, null).orEmpty())
        set(value) = prefs.edit().putString(KEY_TENANT, normalizeTenant(value)).apply()

    var wifionly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, false)
        set(value) = prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()

    var syncIntervalMinutes: Int
        get() = prefs.getInt(KEY_SYNC_INTERVAL, DEFAULT_SYNC_INTERVAL_MINUTES)
        set(value) = prefs.edit().putInt(KEY_SYNC_INTERVAL, value).apply()

    /** Release the user chose to ignore; null means every release is offered again. */
    var skippedUpdateVersion: String?
        get() = prefs.getString(KEY_SKIPPED_UPDATE_VERSION, null)
        set(value) = prefs.edit().putString(KEY_SKIPPED_UPDATE_VERSION, value).apply()

    private companion object {
        const val KEY_TENANT = "tenant"
        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_SYNC_INTERVAL = "sync_interval_minutes"
        const val KEY_SKIPPED_UPDATE_VERSION = "skipped_update_version"
        const val DEFAULT_SYNC_INTERVAL_MINUTES = 30
    }
}
