package com.anderson.onedrivesync.sync

import com.anderson.onedrivesync.db.Entry
import com.anderson.onedrivesync.db.EntryState
import com.anderson.onedrivesync.db.Pair
import com.anderson.onedrivesync.graph.GraphException
import kotlin.coroutines.cancellation.CancellationException

/** Outcome of one [Executor.execute] pass. */
data class ExecStats(val done: Int, val failed: Int)

/**
 * Runs a [Plan] action by action, keeping the index in step with what actually landed on disk and
 * in the drive.
 *
 * A failing action never aborts the plan: it is counted, logged and retried on the next cycle.
 * Only authentication failures (401/403) and an expired delta cursor (410) propagate, because
 * every remaining action would fail the same way.
 *
 * Deletions never reach this class: they wait in the pending queue for user approval.
 */
class Executor(
    private val remote: RemoteApi,
    private val local: LocalFs,
    private val store: IndexStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun execute(
        plan: Plan,
        pair: Pair,
        view: PairView,
        onProgress: (String, Int, Int) -> Unit,
    ): ExecStats {
        // relPath of a remote folder -> its id; "" is the pair root. Seeded with the folders the
        // delta already knows so a nested upload/folder can resolve its parent.
        val remoteIds = HashMap<String, String>()
        remoteIds[""] = pair.remoteId
        for ((relPath, item) in view.items) if (item.isDir) remoteIds[relPath] = item.id

        val actions = plan.actions.sortedBy { it.order() }
        var done = 0
        var failed = 0
        for (action in actions) {
            try {
                run(action, pair, view, remoteIds)
                done++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is GraphException && e.isFatal()) throw e
                failed++
                log("${label(action)} falhou: ${e.message}")
            }
            onProgress(label(action), done + failed, actions.size)
        }
        return ExecStats(done = done, failed = failed)
    }

    private suspend fun run(action: Action, pair: Pair, view: PairView, remoteIds: MutableMap<String, String>) {
        when (action) {
            is Action.CreateFolderRemote -> createFolderRemote(action, pair, view, remoteIds)
            is Action.CreateFolderLocal -> createFolderLocal(action, pair, remoteIds)
            is Action.MoveRemote -> moveRemote(action, pair, remoteIds)
            is Action.MoveLocal -> moveLocal(action, pair)
            is Action.Download -> download(action, pair, view)
            is Action.Upload -> upload(action, pair, remoteIds)
            is Action.DeleteLocal, is Action.DeleteRemote -> Unit // see class doc: pending queue only
        }
    }

    private suspend fun createFolderRemote(
        action: Action.CreateFolderRemote,
        pair: Pair,
        view: PairView,
        remoteIds: MutableMap<String, String>,
    ) {
        val parentRel = parentOf(action.relPath)
        val parentId = checkNotNull(remoteIds[parentRel]) { "pasta remota desconhecida: '$parentRel'" }
        val item = try {
            remote.createFolder(parentId, nameOf(action.relPath))
        } catch (e: GraphException) {
            // A crash between the POST and the index write leaves the folder created; "fail" then
            // answers 409 on every cycle. Adopt the one the view already reports.
            val existing = if (e.isAlreadyExists()) view.items[action.relPath] else null
            existing ?: throw e
        }
        remoteIds[action.relPath] = item.id
        store.upsertEntry(folderEntry(pair, action.relPath, item.id))
    }

    private fun createFolderLocal(action: Action.CreateFolderLocal, pair: Pair, remoteIds: Map<String, String>) {
        local.mkdirs(action.relPath)
        val remoteId = remoteIds[action.relPath] ?: return
        store.upsertEntry(folderEntry(pair, action.relPath, remoteId))
    }

    private suspend fun moveRemote(action: Action.MoveRemote, pair: Pair, remoteIds: MutableMap<String, String>) {
        val parentRel = parentOf(action.to)
        val parentId = checkNotNull(remoteIds[parentRel]) { "pasta remota desconhecida: '$parentRel'" }
        remote.move(action.remoteId, parentId, nameOf(action.to))
        remoteIds[action.to] = remoteIds.remove(action.from) ?: action.remoteId
        rewriteEntries(pair, action.from, action.to)
    }

    private fun moveLocal(action: Action.MoveLocal, pair: Pair) {
        // A crash between the rename and rewriteEntries leaves `to` in place and `from` gone: the
        // move already happened, only the index is stale.
        if (!local.exists(action.from) && local.exists(action.to)) {
            rewriteEntries(pair, action.from, action.to)
            return
        }
        check(local.move(action.from, action.to)) { "não foi possível mover ${action.from} para ${action.to}" }
        rewriteEntries(pair, action.from, action.to)
    }

    private suspend fun download(action: Action.Download, pair: Pair, view: PairView) {
        val relPath = action.relPath
        val item = view.items[relPath]?.takeIf { it.id == action.remoteId }
        val remoteHash = item?.quickXorHash
        val dest = local.file(relPath)

        // Same content already on disk (e.g. a crash between write and index): only fix the index.
        val reuse = remoteHash != null && dest.isFile && local.hash(relPath) == remoteHash
        if (!reuse) {
            local.mkdirs(parentOf(relPath))
            val partRel = "$relPath.part"
            local.file(partRel).delete()
            remote.download(action.remoteId, local.file(partRel)) { _, _ -> }
            check(local.move(partRel, relPath)) { "não foi possível finalizar $relPath" }
        }

        // Never index bytes the drive did not hand over: a truncated or corrupted transfer would
        // look like a local edit next cycle and the planner would upload it over the good copy.
        val actualSize = dest.length()
        val expectedSize = item?.size
        if (expectedSize != null && actualSize != expectedSize) {
            log("download corrompido em $relPath: esperado $expectedSize bytes, obtido $actualSize")
            dest.delete()
            throw IllegalStateException("tamanho inesperado em $relPath (esperado $expectedSize, obtido $actualSize)")
        }
        val actualHash = local.hash(relPath)
        if (remoteHash != null && actualHash != remoteHash) {
            log("download corrompido em $relPath: hash não confere")
            dest.delete()
            throw IllegalStateException("hash não confere em $relPath")
        }

        val remoteMtime = item?.mtimeMs ?: 0
        if (remoteMtime > 0 && !dest.setLastModified(remoteMtime)) log("mtime remoto não aplicado em $relPath")
        store.upsertEntry(
            Entry(
                id = 0,
                pairId = pair.id,
                relPath = relPath,
                parentRel = parentOf(relPath).ifEmpty { null },
                name = nameOf(relPath),
                isDir = false,
                size = actualSize,
                mtimeMs = dest.lastModified(),
                localHash = actualHash,
                remoteId = action.remoteId,
                remoteEtag = null,
                remoteHash = remoteHash,
                syncedAt = clock(),
                state = EntryState.SYNCED,
            ),
        )
    }

    private suspend fun upload(action: Action.Upload, pair: Pair, remoteIds: Map<String, String>) {
        val relPath = action.relPath
        val parentRel = action.remoteParentRel ?: parentOf(relPath)
        val parentId = checkNotNull(remoteIds[parentRel]) { "pasta remota desconhecida: '$parentRel'" }
        val file = local.file(relPath)
        val item = remote.upload(parentId, nameOf(relPath), file) { _, _ -> }
        val mtime = file.lastModified()
        remote.setMtime(item.id, mtime)
        store.upsertEntry(
            Entry(
                id = 0,
                pairId = pair.id,
                relPath = relPath,
                parentRel = parentOf(relPath).ifEmpty { null },
                name = nameOf(relPath),
                isDir = false,
                size = file.length(),
                mtimeMs = mtime,
                localHash = local.hash(relPath),
                remoteId = item.id,
                remoteEtag = null,
                remoteHash = item.quickXorHash ?: remote.getItem(item.id).quickXorHash,
                syncedAt = clock(),
                state = EntryState.SYNCED,
            ),
        )
    }

    /** Re-keys the index entries under [from] (and every descendant) to [to]. */
    private fun rewriteEntries(pair: Pair, from: String, to: String) {
        val prefix = "$from/"
        for (entry in store.entries(pair.id)) {
            val relPath = when {
                entry.relPath == from -> to
                entry.relPath.startsWith(prefix) -> to + entry.relPath.removePrefix(from)
                else -> continue
            }
            if (relPath == entry.relPath) continue
            store.upsertEntry(entry.copy(id = 0, relPath = relPath, parentRel = parentOf(relPath).ifEmpty { null }, name = nameOf(relPath)))
            store.deleteEntry(pair.id, entry.relPath)
        }
    }

    private fun folderEntry(pair: Pair, relPath: String, remoteId: String): Entry = Entry(
        id = 0,
        pairId = pair.id,
        relPath = relPath,
        parentRel = parentOf(relPath).ifEmpty { null },
        name = nameOf(relPath),
        isDir = true,
        size = 0,
        mtimeMs = 0,
        localHash = null,
        remoteId = remoteId,
        remoteEtag = null,
        remoteHash = null,
        syncedAt = clock(),
        state = EntryState.SYNCED,
    )

    private fun log(message: String) = store.log(clock(), "WARN", TAG, message)

    /** Planner order, re-applied so the executor does not depend on the caller's sorting. */
    private fun Action.order(): Int = when (this) {
        is Action.CreateFolderRemote -> 0
        is Action.CreateFolderLocal -> 1
        is Action.MoveRemote -> 2
        is Action.MoveLocal -> 3
        is Action.Download -> 4
        is Action.Upload -> 5
        is Action.DeleteLocal -> 6
        is Action.DeleteRemote -> 7
    }

    private fun label(action: Action): String = when (action) {
        is Action.CreateFolderRemote -> "criar pasta remota ${action.relPath}"
        is Action.CreateFolderLocal -> "criar pasta local ${action.relPath}"
        is Action.MoveRemote -> "mover remoto ${action.from} → ${action.to}"
        is Action.MoveLocal -> "mover local ${action.from} → ${action.to}"
        is Action.Download -> "baixar ${action.relPath}"
        is Action.Upload -> "enviar ${action.relPath}"
        is Action.DeleteLocal -> "excluir local ${action.relPath}"
        is Action.DeleteRemote -> "excluir remoto ${action.relPath}"
    }

    private companion object {
        const val TAG = "Executor"
    }
}

private fun parentOf(relPath: String): String = relPath.substringBeforeLast('/', "")

private fun nameOf(relPath: String): String = relPath.substringAfterLast('/')

private fun GraphException.isFatal(): Boolean = status == 401 || status == 403 || status == 410

/** Graph answers a create that lost the race with the same name as 409 `nameAlreadyExists`. */
private fun GraphException.isAlreadyExists(): Boolean =
    status == 409 || code?.contains("alreadyExists", ignoreCase = true) == true
