package com.anderson.onedrivesync.sync

import com.anderson.onedrivesync.graph.DriveItem

/** One remote node as seen by the delta scan. */
data class RemoteItem(
    val id: String,
    val name: String?,
    val parentId: String?,
    val isDir: Boolean,
    val size: Long,
    val mtimeMs: Long,
    val quickXorHash: String?,
    val deleted: Boolean,
)

/** Maps a Graph delta item; deletions omit [DriveItem.name], so a null name becomes "". */
fun remoteItemOf(dto: DriveItem): RemoteItem = RemoteItem(
    id = dto.id,
    name = dto.name ?: "",
    parentId = dto.parentReference?.id,
    isDir = dto.folder != null,
    size = dto.size ?: 0L,
    mtimeMs = dto.fileSystemInfo?.lastModifiedEpochMs ?: 0L,
    // Graph sends quickXorHash as base64; the index and QuickXorHash use lowercase hex.
    quickXorHash = dto.file?.hashes?.quickXorHash?.let(::quickXorToHex),
    deleted = dto.deleted != null || dto.removed != null,
)

/**
 * Remote tree of one pair: relPath (no leading slash) -> item, plus ids deleted this cycle.
 * [collisions] holds items dropped because another item claimed the same path (or only a case
 * variant of it); they are reported instead of silently overwritten.
 */
data class PairView(
    val items: Map<String, RemoteItem>,
    val deletedIds: Set<String>,
    val collisions: List<RemoteItem> = emptyList(),
)
