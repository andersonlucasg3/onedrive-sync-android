package com.anderson.onedrivesync.notify

import org.junit.Assert.assertEquals
import org.junit.Test

class NotifierTest {

    @Test
    fun `only pending deletions`() {
        assertEquals("3 exclusões pendentes", Notifier.pendingText(pending = 3, conflicts = 0))
    }

    @Test
    fun `only conflicts`() {
        assertEquals("2 conflitos aguardando decisão", Notifier.pendingText(pending = 0, conflicts = 2))
    }

    @Test
    fun `pending deletions and conflicts`() {
        assertEquals(
            "2 exclusões pendentes e 3 conflitos aguardando decisão",
            Notifier.pendingText(pending = 2, conflicts = 3),
        )
    }

    @Test
    fun `singular counts`() {
        assertEquals(
            "1 exclusão pendente e 1 conflito aguardando decisão",
            Notifier.pendingText(pending = 1, conflicts = 1),
        )
    }

    @Test
    fun `nothing queued has no text`() {
        assertEquals("", Notifier.pendingText(pending = 0, conflicts = 0))
    }
}
