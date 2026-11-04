package com.anderson.onedrivesync.sync

import com.anderson.onedrivesync.db.Entry
import com.anderson.onedrivesync.db.EntryState
import com.anderson.onedrivesync.db.Pair
import com.anderson.onedrivesync.graph.GraphException
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Outcome of one [Executor.execute] pass: actions attempted, failures, and how many of the done
 * actions were transfers/moves/folders, so the cycle summary can separate downloads from uploads.
 */
data class ExecStats(
    val done: Int,
    val failed: Int,
    val downloads: Int = 0,
    val uploads: Int = 0,
    val moves: Int = 0,
    val folders: Int = 0,
)

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
    /** Hashes the planner already computed this cycle; reused instead of reading the file twice. */
    private val knownHashes: Map<String, String> = emptyMap(),
) {
    suspend fun execute(
        plan: Plan,
        pair: Pair,
        view: PairView,
        onProgress: (String, Int, Int) -> Unit,
    ): ExecStats {
        // relPath of a remote folder -> its id; "" is the pair root. The index goes first: a folder
        // untouched since the previous cycle does not come back in the delta, so its id survives
        // only there. The view then wins over the index for every folder the delta did report.
        val remoteIds = HashMap<String, String>()
        remoteIds[""] = pair.remoteId
        for (entry in store.entries(pair.id)) {
            val remoteId = entry.remoteId ?: continue
            if (!entry.isDir) continue
            remoteIds.putIfAbsent(entry.relPath, remoteId)
        }
        for ((relPath, item) in view.items) if (item.isDir) remoteIds[relPath] = item.id

        val actions = plan.actions.sortedBy { it.order() }
        var done = 0
        var failed = 0
        var downloads = 0
        var uploads = 0
        var moves = 0
        var folders = 0
        for (action in actions) {
            try {
                run(action, pair, view, remoteIds)
                done++
                when (action) {
                    is Action.Download -> downloads++
                    is Action.Upload -> uploads++
                    is Action.MoveLocal, is Action.MoveRemote -> moves++
                    is Action.CreateFolderLocal, is Action.CreateFolderRemote -> folders++
                    is Action.DeleteLocal, is Action.DeleteRemote -> Unit // never executed here
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is GraphException && e.isFatal()) throw e
                failed++
                // Never leave the log with an empty tail: some exceptions carry no message at all.
                log("${label(action)} falhou: ${e.failureMessage()}")
            }
            onProgress(label(action), done + failed, actions.size)
        }
        return ExecStats(
            done = done,
            failed = failed,
            downloads = downloads,
            uploads = uploads,
            moves = moves,
            folders = folders,
        )
    }

    private suspend fun run(action: Action, pair: Pair, view: PairView, remoteIds: MutableMap<String, String>) {
        when (action) {
            is Action.CreateFolderRemote -> createFolderRemote(action, pair, view, remoteIds)
            is Action.CreateFolderLocal -> createFolderLocal(action, pair, remoteIds)
            is Action.MoveRemote -> moveRemote(action, pair, view, remoteIds)
            is Action.MoveLocal -> moveLocal(action, pair)
            is Action.Download -> download(action, pair, view)
            is Action.Upload -> upload(action, pair, view, remoteIds)
            is Action.DeleteLocal, is Action.DeleteRemote -> Unit // see class doc: pending queue only
        }
    }

    /**
     * Resolves [relPath] to the id of its remote folder, creating the missing segments in order.
     *
     * A folder can be missing from [remoteIds] for two reasons: the delta did not report it (fixed
     * by seeding the index in [execute]) or it genuinely does not exist remotely. The second case
     * used to surface as "pasta remota desconhecida" even though nothing had been tried; here the
     * folder is created under its parent and registered for the rest of the pass.
     *
     * Returns null when a folder could not be created; the creation failure is logged with the
     * folder path, and the caller fails the action instead of uploading into an unknown parent.
     */
    private suspend fun ensureRemoteFolder(
        relPath: String,
        parentOf: (String) -> String,
        remoteIds: MutableMap<String, String>,
        pair: Pair,
        view: PairView,
    ): String? {
        if (relPath.isEmpty()) return remoteIds[""]
        var current = ""
        for (segment in relPath.split('/')) {
            current = if (current.isEmpty()) segment else "$current/$segment"
            if (remoteIds.containsKey(current)) continue
            val parentId = remoteIds[parentOf(current)] ?: return null
            val item = try {
                remote.createFolder(parentId, nameOf(current))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Same race as createFolderRemote: the folder exists but the index lost its id.
                val existing = if (e is GraphException && e.isAlreadyExists()) {
                    view.items[current] ?: resolveExistingFolder(current, parentId)
                } else {
                    null
                }
                if (existing == null) {
                    log("criar pasta remota '$current' falhou: ${e.failureMessage()}")
                    return null
                }
                existing
            }
            requireFolder(item, current)
            remoteIds[current] = item.id
            store.upsertEntry(folderEntry(pair, current, item.id))
        }
        return remoteIds[relPath]
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
            // A crash between the POST and the index write leaves the folder created. `replace` should
            // answer the existing item, but a server that ignores the preference still sends 409:
            // adopt the one the view already reports, or resolve it by path when the delta missed it.
            val existing = if (e.isAlreadyExists()) {
                view.items[action.relPath] ?: resolveExistingFolder(action.relPath, parentId)
            } else {
                null
            }
            existing ?: throw e
        }
        requireFolder(item, action.relPath)
        remoteIds[action.relPath] = item.id
        store.upsertEntry(folderEntry(pair, action.relPath, item.id))
    }

    /**
     * Adopts a folder the drive already has but neither the delta view nor the index knows: a 409
     * only proves the name exists, and Graph's path addressing turns that name into the real id.
     * Returns null when the folder does not exist after all; callers keep their previous failure.
     */
    private suspend fun resolveExistingFolder(relPath: String, parentId: String): RemoteItem? {
        val existing = remote.resolveChild(parentId, nameOf(relPath)) ?: return null
        store.log(clock(), "INFO", TAG, "pasta remota '$relPath' já existia (id via caminho)")
        return existing
    }

    /**
     * `replace` (and the 409 fallback) can answer with a file that owns the folder name; indexing it
     * as a folder would point every child upload at a file.
     */
    private fun requireFolder(item: RemoteItem, relPath: String) {
        if (!item.isDir) {
            throw IllegalStateException("'$relPath' já existe no OneDrive como arquivo, não como pasta")
        }
    }

    private fun createFolderLocal(action: Action.CreateFolderLocal, pair: Pair, remoteIds: Map<String, String>) {
        local.mkdirs(action.relPath)
        val remoteId = remoteIds[action.relPath] ?: return
        store.upsertEntry(folderEntry(pair, action.relPath, remoteId))
    }

    private suspend fun moveRemote(
        action: Action.MoveRemote,
        pair: Pair,
        view: PairView,
        remoteIds: MutableMap<String, String>,
    ) {
        val parentRel = parentOf(action.to)
        val parentId = ensureRemoteFolder(parentRel, ::parentOf, remoteIds, pair, view)
            ?: throw IllegalStateException("pasta remota desconhecida: '$parentRel'")
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

    private suspend fun upload(
        action: Action.Upload,
        pair: Pair,
        view: PairView,
        remoteIds: MutableMap<String, String>,
    ) {
        val relPath = action.relPath
        // A stale or partial view can hide a remote item the plan thinks is new. Never replace
        // identical bytes: index what is already there. Different bytes keep the plan's decision,
        // but leave a trail because the remote copy is being overwritten.
        val existing = view.items[relPath]?.takeIf { !it.isDir }
        val localHash = if (existing != null) knownHashes[relPath] ?: local.hash(relPath) else null
        if (existing != null && localHash != null) {
            val remoteHash = existing.quickXorHash
            if (remoteHash == localHash) {
                indexRemoteCopy(pair, relPath, existing, localHash)
                return
            }
            if (remoteHash != null) log("substituindo remoto existente '$relPath' (hash diferente)")
        }
        val parentRel = action.remoteParentRel ?: parentOf(relPath)
        val parentId = ensureRemoteFolder(parentRel, ::parentOf, remoteIds, pair, view)
            ?: throw IllegalStateException("pasta remota desconhecida: '$parentRel'")
        val file = local.file(relPath)
        val item = remote.upload(parentId, nameOf(relPath), file) { _, _ -> }
        val mtime = file.lastModified()
        applyMtime(item.id, relPath, mtime)
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
                localHash = localHash ?: local.hash(relPath),
                remoteId = item.id,
                remoteEtag = null,
                remoteHash = item.quickXorHash ?: remote.getItem(item.id).quickXorHash,
                syncedAt = clock(),
                state = EntryState.SYNCED,
            ),
        )
    }

    /**
     * Best-effort mtime: the upload already landed, so a refused PATCH must not fail the action.
     * Graph answers 409 `resourceModified` right after the write; failing here would skip the index
     * entry and re-upload the same file on every cycle. Cancellation is never swallowed.
     */
    private suspend fun applyMtime(remoteId: String, relPath: String, mtimeMs: Long) {
        try {
            remote.setMtime(remoteId, mtimeMs)
        } catch (e: Exception) {
            when (e) {
                is CancellationException -> throw e
                is GraphException, is IOException ->
                    log("mtime não aplicado em $relPath: ${e.failureMessage()}")
                else -> throw e
            }
        }
    }

    /** Indexes a remote file whose bytes already match the local copy; nothing is transferred. */
    private fun indexRemoteCopy(pair: Pair, relPath: String, item: RemoteItem, localHash: String) {
        val file = local.file(relPath)
        store.upsertEntry(
            Entry(
                id = 0,
                pairId = pair.id,
                relPath = relPath,
                parentRel = parentOf(relPath).ifEmpty { null },
                name = nameOf(relPath),
                isDir = false,
                size = file.length(),
                mtimeMs = file.lastModified(),
                localHash = localHash,
                remoteId = item.id,
                remoteEtag = null,
                remoteHash = item.quickXorHash,
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

/** A failure message that is never blank: some exceptions (raw I/O, checks) carry none. */
private fun Throwable.failureMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName ?: javaClass.name

private fun GraphException.isFatal(): Boolean = status == 401 || status == 403 || status == 410

/** Graph answers a create that lost the race with the same name as 409 `nameAlreadyExists`. */
private fun GraphException.isAlreadyExists(): Boolean =
    status == 409 || code?.contains("alreadyExists", ignoreCase = true) == true
