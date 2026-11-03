package com.anderson.onedrivesync.work

import org.junit.Assert.assertEquals
import org.junit.Test

class SchedulerTest {

    @Test
    fun `clamps a too small interval up to the 15 minute floor`() {
        assertEquals(15L, Scheduler.clampInterval(5))
    }

    @Test
    fun `keeps the 15 minute floor`() {
        assertEquals(15L, Scheduler.clampInterval(15))
    }

    @Test
    fun `keeps an interval inside the range in minutes`() {
        assertEquals(30L, Scheduler.clampInterval(30))
    }

    @Test
    fun `keeps the 1440 minute ceil`() {
        assertEquals(1440L, Scheduler.clampInterval(1440))
    }

    @Test
    fun `clamps one minute above the ceil`() {
        assertEquals(1440L, Scheduler.clampInterval(1441))
    }

    @Test
    fun `clamps a too large interval down to the 1440 minute ceil`() {
        assertEquals(1440L, Scheduler.clampInterval(5000))
    }

    @Test
    fun `clamps zero up to the floor`() {
        assertEquals(15L, Scheduler.clampInterval(0))
    }

    @Test
    fun `clamps a negative interval up to the floor`() {
        assertEquals(15L, Scheduler.clampInterval(-30))
    }
}
