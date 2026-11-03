package com.anderson.onedrivesync.ui.screens

import androidx.compose.runtime.Composable

/**
 * Destination of the `conflicts` route (notification deep link). It is the pending screen with the
 * conflicts tab preselected, kept as a named entry so the route does not duplicate the tab setup.
 */
@Composable
fun ConflictsScreen(onCountsChanged: () -> Unit = {}) {
    PendingScreen(
        initialTab = PENDING_TAB_CONFLICTS,
        onCountsChanged = onCountsChanged,
    )
}
