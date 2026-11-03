package com.anderson.onedrivesync.ui.util

import java.util.Locale

private const val KB = 1_000.0
private const val MB = 1_000_000.0
private const val GB = 1_000_000_000.0

private val PT_BR: Locale = Locale.forLanguageTag("pt-BR")

/**
 * Short pt-BR size with decimal units, one decimal from KB up: "512 B", "1,2 KB", "3,4 MB".
 *
 * The index stores `0` for a side it never saw (for example the remote side of a deletion
 * conflict), so zero renders as an em dash instead of "0 B". Pure so the format is unit-tested.
 */
fun formatBytes(n: Long): String {
    if (n <= 0) return "—"
    return when {
        n < 1_000 -> "$n B"
        n < 1_000_000 -> decimal(n / KB, "KB")
        n < 1_000_000_000 -> decimal(n / MB, "MB")
        else -> decimal(n / GB, "GB")
    }
}

private fun decimal(value: Double, unit: String): String =
    String.format(PT_BR, "%.1f", value) + " " + unit
