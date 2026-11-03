package com.anderson.onedrivesync.core

import android.content.Context
import com.anderson.onedrivesync.auth.PendingPkce
import com.anderson.onedrivesync.auth.PkceStorage

internal const val PREFS_FILE = "odsync"

class Prefs(context: Context) : PkceStorage {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

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

    override fun savePkce(pkce: PendingPkce) {
        prefs.edit()
            .putString(KEY_PKCE_VERIFIER, pkce.verifier)
            .putString(KEY_PKCE_STATE, pkce.state)
            .putLong(KEY_PKCE_CREATED_AT, pkce.createdAtMs)
            .apply()
    }

    override fun loadPkce(): PendingPkce? {
        val verifier = prefs.getString(KEY_PKCE_VERIFIER, null) ?: return null
        val state = prefs.getString(KEY_PKCE_STATE, null) ?: return null
        return PendingPkce(verifier, state, prefs.getLong(KEY_PKCE_CREATED_AT, 0L))
    }

    override fun clearPkce() {
        prefs.edit()
            .remove(KEY_PKCE_VERIFIER)
            .remove(KEY_PKCE_STATE)
            .remove(KEY_PKCE_CREATED_AT)
            .apply()
    }

    private companion object {
        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_SYNC_INTERVAL = "sync_interval_minutes"
        const val KEY_SKIPPED_UPDATE_VERSION = "skipped_update_version"
        const val KEY_PKCE_VERIFIER = "pkce_verifier"
        const val KEY_PKCE_STATE = "pkce_state"
        const val KEY_PKCE_CREATED_AT = "pkce_created_at"
        const val DEFAULT_SYNC_INTERVAL_MINUTES = 30
    }
}
