package com.anderson.onedrivesync.sync

/** What the engine is doing, for the UI to observe. */
sealed interface SyncState {
    data object Idle : SyncState

    /** Listing the index, hashing changed files and fetching the delta. */
    data object Scanning : SyncState

    data class Running(
        val pairName: String,
        val step: String,
        val done: Int,
        val total: Int,
    ) : SyncState
}
