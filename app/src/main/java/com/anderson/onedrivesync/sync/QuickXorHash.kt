package com.anderson.onedrivesync.sync

import java.io.File
import java.io.FileInputStream

/**
 * Microsoft QuickXorHash (160 bits), streaming.
 * Each input byte is rotated left by `(byteIndex * 11) mod 160` and XORed into a 160-bit ring;
 * the total length (little-endian 64-bit) is XORed into the last 8 bytes on [digest].
 * Spec: https://learn.microsoft.com/onedrive/developer/code-snippets/quickxorhash
 */
class QuickXorHash {
    private val acc = LongArray(WORDS)
    private var shift = 0
    private var length = 0L

    fun update(buf: ByteArray, off: Int = 0, len: Int = buf.size) {
        require(off >= 0 && len >= 0 && off + len <= buf.size) { "range [$off, ${off + len}) out of bounds" }
        for (i in off until off + len) {
            xorAtShift(buf[i].toInt() and 0xFF)
            shift += SHIFT
            if (shift >= WIDTH_BITS) shift -= WIDTH_BITS
            length++
        }
    }

    fun digest(): ByteArray {
        val out = ByteArray(WIDTH_BYTES)
        for (word in 0 until WORDS) {
            var value = acc[word]
            for (byte in 0 until 8) {
                val index = word * 8 + byte
                if (index >= WIDTH_BYTES) break
                out[index] = (value and 0xFF).toByte()
                value = value ushr 8
            }
        }
        var remaining = length
        for (i in 0 until 8) {
            out[WIDTH_BYTES - 8 + i] = (out[WIDTH_BYTES - 8 + i].toInt() xor (remaining and 0xFF).toInt()).toByte()
            remaining = remaining ushr 8
        }
        return out
    }

    fun reset() {
        acc.fill(0)
        shift = 0
        length = 0
    }

    /** XORs one byte starting at the current bit offset, wrapping around the 160-bit ring. */
    private fun xorAtShift(value: Int) {
        var position = shift
        var bitsLeft = 8
        var bits = value
        while (bitsLeft > 0) {
            val word = position / 64
            val offset = position % 64
            val capacity = if (word == WORDS - 1) WIDTH_BITS - 128 else 64
            val take = minOf(bitsLeft, capacity - offset)
            val mask = (1L shl take) - 1
            acc[word] = acc[word] xor ((bits.toLong() and mask) shl offset)
            bits = bits ushr take
            bitsLeft -= take
            position = (position + take) % WIDTH_BITS
        }
    }

    private companion object {
        const val WIDTH_BITS = 160
        const val WIDTH_BYTES = 20
        const val WORDS = 3
        const val SHIFT = 11
    }
}

/** QuickXorHash of [bytes] as 40 lowercase hex chars. */
fun hashBytes(bytes: ByteArray): String {
    val hash = QuickXorHash()
    hash.update(bytes)
    return hash.digest().toHex()
}

/** QuickXorHash of [file] as 40 lowercase hex chars, read in 1 MiB chunks. */
fun hashFile(file: File): String = FileInputStream(file).use { input ->
    val hash = QuickXorHash()
    val buffer = ByteArray(CHUNK_BYTES)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        hash.update(buffer, 0, read)
    }
    hash.digest().toHex()
}

private const val CHUNK_BYTES = 1 shl 20

private const val HEX = "0123456789abcdef"

private fun ByteArray.toHex(): String = buildString(size * 2) {
    for (byte in this@toHex) {
        val value = byte.toInt() and 0xFF
        append(HEX[value ushr 4])
        append(HEX[value and 0x0F])
    }
}
