package com.anderson.onedrivesync.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR

class TimeTextTest {

    @Test
    fun `no timestamp is never`() {
        assertEquals("nunca", relativeTime(nowMs = 1_000L, thenMs = null))
    }

    @Test
    fun `same instant is now`() {
        assertEquals("agora", relativeTime(nowMs = 5_000L, thenMs = 5_000L))
    }

    @Test
    fun `future timestamp is now instead of a negative time`() {
        assertEquals("agora", relativeTime(nowMs = 5_000L, thenMs = 5_000L + 2 * HOUR))
    }

    @Test
    fun `under a minute is now`() {
        assertEquals("agora", relativeTime(nowMs = 10 * MINUTE, thenMs = 10 * MINUTE - 59_999L))
    }

    @Test
    fun `exactly one minute`() {
        assertEquals("há 1 min", relativeTime(nowMs = 10 * MINUTE, thenMs = 9 * MINUTE))
    }

    @Test
    fun `five minutes`() {
        assertEquals("há 5 min", relativeTime(nowMs = 60 * MINUTE, thenMs = 55 * MINUTE))
    }

    @Test
    fun `just under an hour stays in minutes`() {
        assertEquals("há 59 min", relativeTime(nowMs = 100 * HOUR, thenMs = 100 * HOUR - 59 * MINUTE))
    }

    @Test
    fun `three hours`() {
        assertEquals("há 3 h", relativeTime(nowMs = 10 * DAY, thenMs = 10 * DAY - 3 * HOUR))
    }

    @Test
    fun `just under a day stays in hours`() {
        assertEquals("há 23 h", relativeTime(nowMs = 10 * DAY, thenMs = 10 * DAY - 23 * HOUR))
    }

    @Test
    fun `two days`() {
        assertEquals("há 2 d", relativeTime(nowMs = 10 * DAY, thenMs = 8 * DAY))
    }

    @Test
    fun `many days`() {
        assertEquals("há 30 d", relativeTime(nowMs = 100 * DAY, thenMs = 70 * DAY))
    }
}
