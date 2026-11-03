package com.anderson.onedrivesync.ui.util

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS

/**
 * Short pt-BR relative time for list rows.
 *
 * "agora" also covers timestamps in the future: a clock skew must not render "há -3 min".
 * Pure so it can be unit-tested without a `Context`.
 */
fun relativeTime(nowMs: Long, thenMs: Long?): String {
    if (thenMs == null) return "nunca"
    val elapsed = nowMs - thenMs
    return when {
        elapsed < MINUTE_MS -> "agora"
        elapsed < HOUR_MS -> "há ${elapsed / MINUTE_MS} min"
        elapsed < DAY_MS -> "há ${elapsed / HOUR_MS} h"
        else -> "há ${elapsed / DAY_MS} d"
    }
}
