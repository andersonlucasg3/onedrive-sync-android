package com.anderson.onedrivesync.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test

class BytesTest {

    @Test
    fun `zero is unknown instead of zero bytes`() {
        assertEquals("—", formatBytes(0))
    }

    @Test
    fun `negative is unknown`() {
        assertEquals("—", formatBytes(-1))
    }

    @Test
    fun `bytes below a thousand keep the raw number`() {
        assertEquals("512 B", formatBytes(512))
    }

    @Test
    fun `just below a thousand stays in bytes`() {
        assertEquals("999 B", formatBytes(999))
    }

    @Test
    fun `one thousand is one kilobyte`() {
        assertEquals("1,0 KB", formatBytes(1_000))
    }

    @Test
    fun `1024 is still one kilobyte in decimal units`() {
        assertEquals("1,0 KB", formatBytes(1_024))
    }

    @Test
    fun `one and a half megabytes`() {
        assertEquals("1,5 MB", formatBytes(1_500_000))
    }

    @Test
    fun `one gibibyte is one point one gigabytes`() {
        assertEquals("1,1 GB", formatBytes(1_073_741_824))
    }
}
