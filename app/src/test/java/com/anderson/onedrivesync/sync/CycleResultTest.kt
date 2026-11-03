package com.anderson.onedrivesync.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CycleResultTest {

    @Test
    fun `encode and decode preserve every field`() {
        val result = CycleResult(
            finishedAt = 1_700_000_000_123,
            ok = true,
            downloaded = 3,
            uploaded = 2,
            failed = 0,
            pending = 1,
            conflicts = 4,
            error = "linha de erro",
        )

        val decoded = decodeCycleResult(encodeCycleResult(result))

        assertEquals(result, decoded)
    }

    @Test
    fun `error is optional and stays null`() {
        val result = CycleResult(1_700_000_000_000, false, 0, 0, 1, 0, 0)

        val decoded = decodeCycleResult(encodeCycleResult(result))

        assertEquals(result, decoded)
        assertNull(decoded?.error)
    }

    @Test
    fun `decode null blank garbage or incomplete json returns null`() {
        assertNull(decodeCycleResult(null))
        assertNull(decodeCycleResult(""))
        assertNull(decodeCycleResult("   "))
        assertNull(decodeCycleResult("lixo"))
        assertNull(decodeCycleResult("{"))
        assertNull(decodeCycleResult("{}"))
        assertNull(decodeCycleResult("""{"finishedAt":1}"""))
        assertNull(decodeCycleResult("""{"finishedAt":"não é número"}"""))
    }

    @Test
    fun `a multi line error survives the round trip`() {
        val result = CycleResult(1, false, 0, 0, 0, 0, 0, "primeira linha\nsegunda linha")

        val decoded = decodeCycleResult(encodeCycleResult(result))

        assertEquals("primeira linha\nsegunda linha", decoded?.error)
    }

    @Test
    fun `keys are global and per pair`() {
        assertEquals("cycle.last", CycleResultKeys.GLOBAL)
        assertEquals("cycle.last.7", CycleResultKeys.pair(7))
    }
}
