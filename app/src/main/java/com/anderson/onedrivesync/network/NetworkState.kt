package com.anderson.onedrivesync.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * True when the active network is Wi-Fi. Any failure (no network, missing permission) counts as
 * "not Wi-Fi": the Wi-Fi-only setting must never sync over a metered connection by accident.
 */
fun isOnWifi(context: Context): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
    return try {
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    } catch (_: SecurityException) {
        false
    }
}
