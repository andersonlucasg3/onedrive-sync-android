package com.anderson.onedrivesync.sync

import java.util.Base64

/** Graph sends quickXorHash as base64; the local index and QuickXorHash use lowercase hex. */
internal fun quickXorToHex(raw: String): String {
    val value = raw.trim()
    if (value.length == HEX_LENGTH && value.all(::isHexDigit)) return value.lowercase()
    return try {
        Base64.getDecoder().decode(value).toHex()
    } catch (_: IllegalArgumentException) {
        value.lowercase()
    }
}

private const val HEX_LENGTH = 40

private fun isHexDigit(c: Char): Boolean =
    c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

private const val HEX = "0123456789abcdef"

private fun ByteArray.toHex(): String = buildString(size * 2) {
    for (byte in this@toHex) {
        val value = byte.toInt() and 0xFF
        append(HEX[value ushr 4])
        append(HEX[value and 0x0F])
    }
}
