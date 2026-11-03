package com.anderson.onedrivesync.sync

import com.anderson.onedrivesync.db.ConflictRow
import com.anderson.onedrivesync.db.Entry
import com.anderson.onedrivesync.db.LocalFile
import com.anderson.onedrivesync.db.PendingKind
import com.anderson.onedrivesync.db.PendingOp

/** One operation the executor (F3b) can run. Deletions never appear here: they go to [Plan.pending]. */
sealed interface Action {
    data class CreateFolderLocal(val relPath: String) : Action
    data class CreateFolderRemote(val relPath: String) : Action
    data class Download(val relPath: String, val remoteId: String, val size: Long) : Action
    data class Upload(val relPath: String, val remoteParentRel: String?) : Action
    data class MoveLocal(val from: String, val to: String) : Action
    data class MoveRemote(val from: String, val to: String, val remoteId: String) : Action
    data class DeleteLocal(val relPath: String) : Action
    data class DeleteRemote(val relPath: String, val remoteId: String) : Action
}

data class Plan(
    val actions: List<Action>,
    val pending: List<PendingOp>,
    val conflicts: List<ConflictRow>,
)

/**
 * Pure decision table over index (`prev`), remote tree and local scan. No IO, no Android.
 *
 * `localHashes` carries hashes the scanner already computed. A missing hash means size+mtime matched
 * the index and the caller skipped hashing, so [localChanged] trusts size+mtime in that case.
 */
object Planner {
    fun plan(
        pairId: Long,
        prev: List<Entry>,
        view: PairView,
        local: List<LocalFile>,
        localHashes: Map<String, String>,
        now: Long,
    ): Plan {
        val prevByRel = prev.associateBy { it.relPath }
        val prevByRemoteId = prev.mapNotNull { entry -> entry.remoteId?.let { id -> id to entry } }.toMap()
        val localMap = local.associateBy { it.relPath }
        val remotePaths = view.items.entries.associate { it.value.id to it.key }

        val movedRemote = HashMap<String, String>() // remoteId -> new relPath
        for ((path, item) in view.items) {
            val indexed = prevByRemoteId[item.id] ?: continue
            if (indexed.relPath != path) movedRemote[item.id] = path
        }

        val actions = ArrayList<Action>()
        val pending = ArrayList<PendingOp>()
        val conflicts = ArrayList<ConflictRow>()
        val consumed = HashSet<String>()

        // Remote moves: only the highest changed node emits; its descendants follow it.
        for (entry in prev.sortedBy { it.relPath }) {
            val remoteId = entry.remoteId ?: continue
            val newPath = movedRemote[remoteId] ?: continue
            if (isHiddenRelPath(entry.relPath) || isHiddenRelPath(newPath)) continue
            consumed += newPath
            if (!parentMoved(entry, prevByRel, movedRemote)) {
                actions += Action.MoveLocal(entry.relPath, newPath)
            }
        }

        // Local moves: a vanished indexed file whose size+hash reappear at a brand-new local path.
        val moveTargets = HashSet<String>()
        for (entry in prev.sortedBy { it.relPath }) {
            val remoteId = entry.remoteId ?: continue
            if (isHiddenRelPath(entry.relPath)) continue
            if (entry.isDir || entry.localHash == null || localMap.containsKey(entry.relPath)) continue
            if (remoteId in movedRemote || remoteId in view.deletedIds) continue
            val atPath = view.items[entry.relPath]?.takeIf { it.id == remoteId }
            if (atPath != null && remoteChanged(entry, atPath)) continue
            val target = localMap.values
                .filter { candidate ->
                    !candidate.isDir &&
                        candidate.relPath != entry.relPath &&
                        !prevByRel.containsKey(candidate.relPath) &&
                        candidate.relPath !in view.items &&
                        candidate.relPath !in moveTargets &&
                        candidate.size == entry.size &&
                        localHashes[candidate.relPath] == entry.localHash
                }
                .minByOrNull { it.relPath } ?: continue
            if (isHiddenRelPath(target.relPath)) continue
            actions += Action.MoveRemote(entry.relPath, target.relPath, remoteId)
            consumed += entry.relPath
            consumed += target.relPath
            moveTargets += target.relPath
        }

        val paths = sortedSetOf<String>()
        paths.addAll(prevByRel.keys)
        paths.addAll(view.items.keys)
        paths.addAll(localMap.keys)

        for (relPath in paths) {
            // Safety net: whatever escaped the scan and the delta, a hidden path gets no action,
            // no conflict and no pending deletion.
            if (isHiddenRelPath(relPath)) continue
            if (relPath in consumed) continue
            val entry = prevByRel[relPath]
            val remote = view.items[relPath]
            val localFile = localMap[relPath]
            val localHash = localHashes[relPath]

            if (entry == null) {
                when {
                    remote == null -> when {
                        localFile == null -> Unit
                        localFile.isDir -> actions += Action.CreateFolderRemote(relPath)
                        else -> actions += Action.Upload(relPath, remoteParentOf(relPath, view))
                    }
                    localFile == null ->
                        if (remote.isDir) actions += Action.CreateFolderLocal(relPath)
                        else actions += Action.Download(relPath, remote.id, remote.size)
                    remote.isDir || localFile.isDir -> {
                        if (!(remote.isDir && localFile.isDir)) {
                            conflicts += conflictRow(pairId, relPath, remote.id, remote, localFile, now)
                        }
                    }
                    localFile.size == remote.size && localHash != null && localHash == remote.quickXorHash -> Unit
                    else -> conflicts += conflictRow(pairId, relPath, remote.id, remote, localFile, now)
                }
                continue
            }

            val remoteId = entry.remoteId
            if (remote != null && remoteId != null && remote.id != remoteId) {
                // Another item now owns this path; plan it as new, the indexed one is unreported.
                when {
                    localFile == null -> actions += Action.Download(relPath, remote.id, remote.size)
                    remote.size == localFile.size && localHash != null && localHash == remote.quickXorHash -> Unit
                    else -> conflicts += conflictRow(pairId, relPath, remote.id, remote, localFile, now)
                }
            } else if (remoteId != null && remoteId in view.deletedIds) {
                when {
                    localFile == null -> Unit
                    localChanged(entry, localFile, localHash) ->
                        conflicts += conflictRow(pairId, relPath, remoteId, null, localFile, now)
                    entry.isDir && hasLocalDescendants(relPath, localMap) ->
                        conflicts += conflictRow(pairId, relPath, remoteId, null, localFile, now)
                    else -> pending += pendingOp(pairId, PendingKind.DELETE_LOCAL, relPath, remoteId, "removido no OneDrive", now)
                }
            } else if (localFile == null) {
                when {
                    remote == null && remoteId == null -> Unit
                    remote != null && remoteChanged(entry, remote) ->
                        actions += Action.Download(relPath, remote.id, remote.size)
                    else -> pending += pendingOp(
                        pairId,
                        PendingKind.DELETE_REMOTE,
                        relPath,
                        remote?.id ?: remoteId,
                        "removido localmente",
                        now,
                    )
                }
            } else if (remote == null) {
                when {
                    entry.isDir ->
                        if (remoteId == null) actions += Action.CreateFolderRemote(relPath)
                    remoteId == null || localChanged(entry, localFile, localHash) ->
                        actions += Action.Upload(relPath, remoteParentOf(relPath, view))
                }
            } else {
                val remoteChangedFlag = remoteChanged(entry, remote)
                val localChangedFlag = localChanged(entry, localFile, localHash)
                when {
                    remoteChangedFlag && localChangedFlag ->
                        if (localHash != null && localHash == remote.quickXorHash) Unit
                        else conflicts += conflictRow(pairId, relPath, remote.id, remote, localFile, now)
                    remoteChangedFlag -> actions += Action.Download(relPath, remote.id, remote.size)
                    localChangedFlag -> actions += Action.Upload(relPath, remoteParentOf(relPath, view))
                }
            }
        }

        actions.sortBy { it.rank() }
        return Plan(
            actions = actions,
            pending = pending.sortedBy { it.relPath },
            conflicts = conflicts.sortedBy { it.relPath },
        )
    }

    /** Executor order: create folders, move, transfer. */
    private fun Action.rank(): Int = when (this) {
        is Action.CreateFolderRemote -> 0
        is Action.CreateFolderLocal -> 1
        is Action.MoveRemote -> 2
        is Action.MoveLocal -> 3
        is Action.Download -> 4
        is Action.Upload -> 5
        is Action.DeleteLocal -> 6
        is Action.DeleteRemote -> 7
    }

    private fun parentMoved(entry: Entry, prevByRel: Map<String, Entry>, movedRemote: Map<String, String>): Boolean {
        val parentRemoteId = entry.parentRel?.let { prevByRel[it] }?.remoteId ?: return false
        return parentRemoteId in movedRemote
    }

    /** Remote differs from what the index recorded, by size or (when both sides have it) hash. */
    private fun remoteChanged(entry: Entry, remote: RemoteItem): Boolean {
        if (remote.size != entry.size) return true
        return remote.quickXorHash != null && entry.remoteHash != null && remote.quickXorHash != entry.remoteHash
    }

    /** Local intact = same size, mtime and (when known) hash as the index. */
    private fun localChanged(entry: Entry, localFile: LocalFile, localHash: String?): Boolean {
        if (localFile.size != entry.size || localFile.mtimeMs != entry.mtimeMs) return true
        if (localHash == null || entry.localHash == null) return false
        return localHash != entry.localHash
    }

    private fun hasLocalDescendants(relPath: String, localMap: Map<String, LocalFile>): Boolean {
        val prefix = "$relPath/"
        return localMap.keys.any { it.startsWith(prefix) }
    }

    /** Remote parent folder; null when that parent is not (yet) known on the remote side. */
    private fun remoteParentOf(relPath: String, view: PairView): String? {
        val slash = relPath.lastIndexOf('/')
        if (slash < 0) return ""
        val parent = relPath.substring(0, slash)
        return if (view.items[parent]?.isDir == true) parent else null
    }

    private fun conflictRow(
        pairId: Long,
        relPath: String,
        remoteId: String?,
        remote: RemoteItem?,
        localFile: LocalFile?,
        now: Long,
    ): ConflictRow = ConflictRow(
        id = 0,
        pairId = pairId,
        relPath = relPath,
        remoteId = remoteId ?: remote?.id,
        localMtimeMs = localFile?.mtimeMs ?: 0L,
        remoteMtimeMs = remote?.mtimeMs ?: 0L,
        localSize = localFile?.size ?: 0L,
        remoteSize = remote?.size ?: 0L,
        decision = null,
        createdAt = now,
    )

    private fun pendingOp(
        pairId: Long,
        kind: PendingKind,
        relPath: String,
        remoteId: String?,
        detail: String,
        now: Long,
    ): PendingOp = PendingOp(
        id = 0,
        pairId = pairId,
        kind = kind,
        relPath = relPath,
        remoteId = remoteId,
        detail = detail,
        createdAt = now,
    )
}
