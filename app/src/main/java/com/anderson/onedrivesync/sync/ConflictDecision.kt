package com.anderson.onedrivesync.sync

/** How the user settled a conflict: keep the local copy, the remote copy, or both. */
enum class ConflictDecision { KEEP_LOCAL, KEEP_REMOTE, KEEP_BOTH }
