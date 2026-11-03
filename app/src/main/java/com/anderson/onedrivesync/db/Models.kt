package com.anderson.onedrivesync.db

import java.io.File

/** A remote OneDrive folder paired with a local directory. */
data class Pair(
    val id: Long,
    val remoteId: String,
    val remotePath: String,
    val localPath: String,
    val displayName: String,
    val enabled: Boolean,
    val createdAt: Long,
    val lastSyncAt: Long?,
)

/** Indexed state of one path inside a pair. */
data class Entry(
    val id: Long,
    val pairId: Long,
    val relPath: String,
    val parentRel: String?,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val mtimeMs: Long,
    val localHash: String?,
    val remoteId: String?,
    val remoteEtag: String?,
    val remoteHash: String?,
    val syncedAt: Long,
    val state: EntryState,
)

enum class EntryState { SYNCED, LOCAL_NEW, REMOTE_NEW, DIRTY }

/** A queued operation awaiting execution or user confirmation. */
data class PendingOp(
    val id: Long,
    val pairId: Long,
    val kind: PendingKind,
    val relPath: String,
    val remoteId: String?,
    val detail: String?,
    val createdAt: Long,
)

enum class PendingKind { DOWNLOAD, UPLOAD, MOVE_LOCAL, MOVE_REMOTE, DELETE_LOCAL, DELETE_REMOTE, CONFLICT }

/** Both sides changed for [relPath]; waits for a user decision. */
data class ConflictRow(
    val id: Long,
    val pairId: Long,
    val relPath: String,
    val remoteId: String?,
    val localMtimeMs: Long,
    val remoteMtimeMs: Long,
    val localSize: Long,
    val remoteSize: Long,
    val decision: String?,
    val createdAt: Long,
)

/** One entry produced by [com.anderson.onedrivesync.sync.LocalScanner]. */
data class LocalFile(
    val relPath: String,
    val isDir: Boolean,
    val size: Long,
    val mtimeMs: Long,
    val file: File,
)
