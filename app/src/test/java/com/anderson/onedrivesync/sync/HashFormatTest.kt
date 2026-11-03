package com.anderson.onedrivesync.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class HashFormatTest {
    @Test
    fun `decodes the base64 hash graph sent for a real three byte file`() {
        assertEquals(VECTOR_HEX, quickXorToHex(VECTOR_BASE64))
    }

    @Test
    fun `keeps an already hex hash and lowercases it`() {
        assertEquals(VECTOR_HEX, quickXorToHex(VECTOR_HEX.uppercase()))
    }

    @Test
    fun `invalid base64 is returned lowercased instead of throwing`() {
        assertEquals("não é base64!!!", quickXorToHex("Não é Base64!!!"))
    }

    @Test
    fun `empty input stays empty`() {
        assertEquals("", quickXorToHex(""))
        assertEquals("", quickXorToHex("   "))
    }

    private companion object {
        const val VECTOR_BASE64 = "e+iDAgAAAAAAAAAAAwAAAAAAAAA="
        const val VECTOR_HEX = "7be8830200000000000000000300000000000000"
    }
}
