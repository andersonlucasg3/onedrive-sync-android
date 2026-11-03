package com.anderson.onedrivesync.notify

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * `progressNotification` needs real resources to build a [android.app.Notification], so the whole
 * class runs under Robolectric (same setup as `DbTest`); [Notifier.pendingText] stays pure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
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

    // A notification posted with small icon 0 throws "Invalid notification (no valid small icon)"
    // and kills the foreground service; this guards the icon resolution for every builder.
    @Test
    fun `progress notification always carries a small icon`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val notification = Notifier.progressNotification(context, "x", 0, 0, null)
        assertTrue("notification.icon must not be 0", notification.icon != 0)
        assertNotNull("notification.smallIcon must not be null", notification.smallIcon)
    }
}
