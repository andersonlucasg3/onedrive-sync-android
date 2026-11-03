package com.anderson.onedrivesync.sync

import com.anderson.onedrivesync.db.ConflictRow
import com.anderson.onedrivesync.db.Entry
import com.anderson.onedrivesync.db.EntryState
import com.anderson.onedrivesync.db.LocalFile
import com.anderson.onedrivesync.db.Pair
import com.anderson.onedrivesync.db.PendingKind
import com.anderson.onedrivesync.db.PendingOp
import com.anderson.onedrivesync.graph.GraphException
import com.anderson.onedrivesync.graph.isGone
import com.anderson.onedrivesync.graph.isNotFound
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Orchestrates one sync cycle: local scan, delta fetch, planner, executor, index persistence.
 *
 * One cycle at a time ([Mutex]); [cancel] cancels the job the cycle runs in, so the caller's
 * coroutine sees a normal `CancellationException` and nothing is left half-locked.
 *
 * The delta cursor (`delta_link`) is drive-wide. [syncAll] therefore enumerates once and plans
 * every pair against the same items, advancing the cursor only after all pairs ran; otherwise the
 * second pair would consume an already-advanced delta and never see remote changes.
 */
class SyncEngine(
    private val store: IndexStore,
    private val remote: RemoteApi,
    private val localFsFor: (Pair) -> LocalFs,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()
    private var job: Job? = null

    suspend fun syncAll(
        onNotify: (List<PendingOp>, List<ConflictRow>) -> Unit = { _, _ -> },
    ): CycleResult? = runCycle(store.listPairs(enabledOnly = true), onNotify)

    suspend fun syncPair(
        pairId: Long,
        onNotify: (List<PendingOp>, List<ConflictRow>) -> Unit = { _, _ -> },
    ): CycleResult? = runCycle(listOfNotNull(store.getPair(pairId)), onNotify)

    fun cancel() {
        job?.cancel()
    }

    /** DELETE_LOCAL: deletes the local file and its index entries. DELETE_REMOTE: same on the drive. */
    suspend fun approvePending(id: Long) {
        mutex.withLock {
            val op = store.getPending(id) ?: return
            val pair = store.getPair(op.pairId)
            if (pair == null) {
                store.deletePending(id)
                return
            }
            val fs = localFsFor(pair)
            when (op.kind) {
                PendingKind.DELETE_LOCAL -> {
                    if (!fs.delete(op.relPath)) log("não foi possível apagar ${op.relPath} localmente")
                    removeEntries(pair.id, op.relPath)
                    store.deletePending(id)
                }
                PendingKind.DELETE_REMOTE -> {
                    val remoteId = op.remoteId
                        ?: store.entries(pair.id).firstOrNull { it.relPath == op.relPath }?.remoteId
                    if (remoteId != null) remote.delete(remoteId)
                    removeEntries(pair.id, op.relPath)
                    store.deletePending(id)
                }
                else -> Unit // transfers are never queued; only deletions wait for approval
            }
        }
    }

    /** Drops the pending op without touching either side. */
    suspend fun rejectPending(id: Long) {
        mutex.withLock { store.deletePending(id) }
    }

    suspend fun resolveConflict(id: Long, decision: ConflictDecision) {
        mutex.withLock {
            val row = store.getConflict(id) ?: return
            val pair = store.getPair(row.pairId)
            if (pair == null) {
                store.deleteConflict(id)
                return
            }
            val resolved = when (decision) {
                ConflictDecision.KEEP_LOCAL -> keepLocal(pair, row)
                ConflictDecision.KEEP_REMOTE -> keepRemote(pair, row)
                ConflictDecision.KEEP_BOTH -> keepBoth(pair, row)
            }
            if (resolved) store.deleteConflict(id)
        }
    }

    private suspend fun runCycle(
        pairs: List<Pair>,
        onNotify: (List<PendingOp>, List<ConflictRow>) -> Unit,
    ): CycleResult? {
        if (pairs.isEmpty()) {
            logInfo("ciclo ignorado: nenhum par ativo")
            return null
        }
        val self = currentCoroutineContext()[Job]
        var outcome: CycleResult? = null
        mutex.withLock {
            job = self
            logInfo("início do ciclo: ${pairs.size} par(es)")
            var done = 0
            var failed = 0
            var pending = 0
            var conflicts = 0
            var downloads = 0
            var uploads = 0
            var error: String? = null
            var canceled = false
            val recorded = HashSet<Long>()
            // The delta is drive-wide: with a single pair its card owns the phase, with several only
            // the global banner can claim it (the name matches no card on purpose).
            val label = pairs.singleOrNull()?.displayName.orEmpty()
            try {
                _state.value = SyncState.Running(label, "lendo OneDrive", 0, 0)
                val deltaStart = clock()
                // The cursor is drive-wide. A pair that never finished a cycle has an empty index,
                // so a cursor-only delta would leave it empty until something changes remotely.
                val delta = fetchDelta(forceFull = pairs.any { it.lastSyncAt == null })
                logInfo("delta: ${delta.items.size} itens em ${delta.pages} página(s) em ${clock() - deltaStart} ms")
                if (!delta.complete) {
                    val message = "delta truncado em $MAX_PAGES páginas: drive maior do que o suportado; " +
                        "nenhuma ação foi aplicada neste ciclo"
                    log(message, "ERROR")
                    error = message
                    for (pair in pairs) {
                        store.setState(CycleResultKeys.pair(pair.id), encodeCycleResult(failedResult(message)))
                        recorded += pair.id
                    }
                    return@withLock
                }
                for (pair in pairs) {
                    val stats = runPair(pair, delta, onNotify)
                    recorded += pair.id
                    done += stats.done
                    failed += stats.failed
                    pending += stats.pending
                    conflicts += stats.conflicts
                    downloads += stats.downloads
                    uploads += stats.uploads
                }
                // Only advance when the enumeration reached its delta link and no action failed. A
                // failed action leaves the remote item unchanged, so it never comes back in a later
                // delta and would be lost. Replaying the same window is safe (planner and executor
                // are idempotent) and the delta from the same token is cumulative.
                if (delta.deltaLink != null) {
                    if (failed == 0) {
                        store.setState(KEY_DELTA_LINK, delta.deltaLink)
                    } else {
                        log("$failed falha(s) no ciclo: cursor delta mantido para repetir a mesma janela")
                    }
                }
            } catch (e: CancellationException) {
                canceled = true
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                // Pairs that never got to run still deserve a failed status instead of the stale one.
                for (pair in pairs) {
                    if (pair.id in recorded) continue
                    store.setState(CycleResultKeys.pair(pair.id), encodeCycleResult(failedResult(error)))
                }
                throw e
            } finally {
                job = null
                _state.value = SyncState.Idle
                if (!canceled) {
                    val result = CycleResult(
                        finishedAt = clock(),
                        ok = error == null && failed == 0,
                        downloaded = downloads,
                        uploaded = uploads,
                        failed = failed,
                        pending = pending,
                        conflicts = conflicts,
                        error = error,
                    )
                    outcome = result
                    store.setState(CycleResultKeys.GLOBAL, encodeCycleResult(result))
                    logInfo(
                        "fim do ciclo: ok=$done falhas=$failed, pendências=$pending, conflitos=$conflicts" +
                            (error?.let { ", erro: $it" } ?: ""),
                    )
                }
            }
        }
        return outcome
    }

    private fun failedResult(message: String): CycleResult = CycleResult(
        finishedAt = clock(),
        ok = false,
        downloaded = 0,
        uploaded = 0,
        failed = 0,
        pending = 0,
        conflicts = 0,
        error = message,
    )

    private suspend fun runPair(
        pair: Pair,
        delta: DeltaFetch,
        onNotify: (List<PendingOp>, List<ConflictRow>) -> Unit,
    ): PairStats {
        val name = pair.displayName
        val fs = localFsFor(pair)
        var exec: ExecStats? = null
        var pendingCount = 0
        var conflictCount = 0
        var error: String? = null
        var canceled = false
        try {
            val prev = store.entries(pair.id)

            _state.value = SyncState.Running(name, "lendo pastas locais", 0, 0)
            val scanStart = clock()
            val localFiles = fs.scan()
            logInfo("scan local: ${localFiles.size} entradas em ${clock() - scanStart} ms")

            val view = RemoteTree.resolve(pair.remoteId, prev, delta.items)

            _state.value = SyncState.Running(name, "calculando hashes", 0, 0)
            val hashStart = clock()
            val toHash = filesToHash(prev, localFiles, view)
            val hashes = hashFiles(toHash, fs) { hashed, total ->
                _state.value = SyncState.Running(name, "calculando hashes", hashed, total)
            }
            logInfo("hashes: ${hashes.size}/${toHash.size} em ${clock() - hashStart} ms")

            _state.value = SyncState.Running(name, "planejando", 0, 0)
            val planStart = clock()
            val plan = Planner.plan(pair.id, prev, view, localFiles, hashes, clock())

            // After a 410 the delta is a fresh full enumeration: absence is not proof of deletion.
            val pending = if (delta.resync) {
                plan.pending.filterNot { it.kind == PendingKind.DELETE_LOCAL || it.kind == PendingKind.DELETE_REMOTE }
            } else {
                plan.pending
            }
            logInfo(
                "plano: ${plan.actions.count { it is Action.Download }} downloads, " +
                    "${plan.actions.count { it is Action.Upload }} uploads, " +
                    "${plan.actions.count { it is Action.MoveLocal || it is Action.MoveRemote }} moves, " +
                    "${pending.size} pendências, ${plan.conflicts.size} conflitos em ${clock() - planStart} ms",
            )

            val stats = Executor(remote, fs, store, clock).execute(plan, pair, view) { step, done, total ->
                _state.value = SyncState.Running(name, step, done, total)
            }
            exec = stats

            val newPending = persistPending(pending)
            val newConflicts = persistConflicts(plan.conflicts)
            pendingCount = newPending.size
            conflictCount = newConflicts.size
            // A new pair stays "never synced" until a clean cycle, so the next attempt still forces a
            // full enumeration: holding the cursor alone would replay only the changes after the old
            // token, and items that did not change would never come back for this pair.
            if (stats.failed == 0) store.touchPair(pair.id, clock())
            if (newPending.isNotEmpty() || newConflicts.isNotEmpty()) onNotify(newPending, newConflicts)
            return PairStats(
                done = stats.done,
                failed = stats.failed,
                pending = pendingCount,
                conflicts = conflictCount,
                downloads = stats.downloads,
                uploads = stats.uploads,
            )
        } catch (e: CancellationException) {
            canceled = true
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
            throw e
        } finally {
            // Every finished attempt leaves a result, including the ones that failed before the
            // executor ran: the card must never fall back to a stale "sincronizado" silently.
            if (!canceled) {
                val stats = exec
                store.setState(
                    CycleResultKeys.pair(pair.id),
                    encodeCycleResult(
                        CycleResult(
                            finishedAt = clock(),
                            ok = error == null && (stats?.failed ?: 0) == 0,
                            downloaded = stats?.downloads ?: 0,
                            uploaded = stats?.uploads ?: 0,
                            failed = stats?.failed ?: 0,
                            pending = pendingCount,
                            conflicts = conflictCount,
                            error = error,
                        ),
                    ),
                )
            }
        }
    }

    /**
     * Only files whose content can still change the decision are hashed:
     * - a remote file exists at the same path with the same size (content is the only evidence left;
     *   a different size already proves the sides differ);
     * - the index has the same path with the same size but a different mtime (a touch vs a real edit);
     * - the file may be the other side of a local rename: it has the size of an indexed file that
     *   vanished, which the planner confirms by hash.
     *
     * A brand-new local file with no remote counterpart is uploaded as-is. Hashing it first would
     * read the whole gallery twice, which is what made a first sync sit on "Scanning" for minutes.
     */
    private fun filesToHash(prev: List<Entry>, localFiles: List<LocalFile>, view: PairView): List<LocalFile> {
        val indexed = prev.associateBy { it.relPath }
        val present = localFiles.mapTo(HashSet()) { it.relPath }
        val vanishedSizes = prev.asSequence()
            .filter {
                !it.isDir && it.localHash != null && it.remoteId != null &&
                    it.relPath !in present && it.remoteId !in view.deletedIds
            }
            .mapTo(HashSet()) { it.size }
        return localFiles.filter { file ->
            if (file.isDir) return@filter false
            val entry = indexed[file.relPath]
            val remote = view.items[file.relPath]
            when {
                remote != null && !remote.isDir && remote.size == file.size -> true
                entry != null && entry.size == file.size && entry.mtimeMs != file.mtimeMs -> true
                entry == null && vanishedSizes.isNotEmpty() && file.size in vanishedSizes -> true
                else -> false
            }
        }
    }

    /** Hashes [files], reporting progress every [HASH_PROGRESS_FILES] files or [HASH_PROGRESS_MS] ms. */
    private fun hashFiles(
        files: List<LocalFile>,
        fs: LocalFs,
        onProgress: (Int, Int) -> Unit,
    ): Map<String, String> {
        if (files.isEmpty()) return emptyMap()
        val hashes = HashMap<String, String>(files.size)
        var lastEmit = 0L
        for ((index, file) in files.withIndex()) {
            hashes[file.relPath] = fs.hash(file.relPath)
            val done = index + 1
            val now = clock()
            if (done == files.size || done % HASH_PROGRESS_FILES == 0 || now - lastEmit >= HASH_PROGRESS_MS) {
                lastEmit = now
                onProgress(done, files.size)
            }
        }
        return hashes
    }

    private suspend fun fetchDelta(forceFull: Boolean = false): DeltaFetch {
        val cursor = if (forceFull) null else store.getState(KEY_DELTA_LINK)
        if (forceFull) log("par sem sincronização anterior: reenumerando o drive do zero")
        val started = clock()
        // A full enumeration is legitimate, not a 410 resync: tombstones that do arrive in the
        // delta must still produce DELETE_* pending ops, so resync stays false on this path.
        return try {
            val page = pages(cursor)
            logInfo("delta: ${page.items.size} itens em ${page.pages} página(s) em ${clock() - started} ms")
            DeltaFetch(page.items, page.deltaLink, resync = false, complete = page.complete, pages = page.pages)
        } catch (e: GraphException) {
            if (!e.isGone()) throw e
            log("delta expirou (410 Gone): reenumerando do zero" + (e.code?.let { " (código $it)" } ?: ""))
            store.setState(KEY_DELTA_LINK, null)
            val page = pages(null)
            logInfo("delta: ${page.items.size} itens em ${page.pages} página(s) em ${clock() - started} ms")
            DeltaFetch(page.items, page.deltaLink, resync = true, complete = page.complete, pages = page.pages)
        }
    }

    private suspend fun pages(cursor: String?): Page {
        // The same item may be reported more than once in one cycle; the last occurrence wins.
        val items = LinkedHashMap<String, RemoteItem>()
        var next: String? = cursor
        var count = 0
        while (count < MAX_PAGES) {
            val page = remote.deltaPage(next)
            for (item in page.items) items[item.id] = item
            count++
            if (page.nextLink == null) return Page(items.values.toList(), page.deltaLink, pages = count)
            // One line per page: an enumeration of a big drive takes minutes and must leave a trail
            // in the activity log, which is the only diagnostic the user can send.
            logInfo("delta: página $count, ${items.size} itens até agora")
            next = page.nextLink
        }
        // Planning against a partial drive view would invent uploads for items we simply have not
        // enumerated yet. The cursor stays put so the same range is retried; the log is the only
        // symptom the user can report.
        return Page(items.values.toList(), null, complete = false, pages = count)
    }

    private suspend fun keepLocal(pair: Pair, row: ConflictRow): Boolean {
        val fs = localFsFor(pair)
        if (!fs.file(row.relPath).isFile) return false
        val view = folderView(store.entries(pair.id), emptyMap())
        val plan = Plan(listOf(Action.Upload(row.relPath, parentOf(row.relPath))), emptyList(), emptyList())
        return runResolution(plan, pair, fs, view)
    }

    private suspend fun keepRemote(pair: Pair, row: ConflictRow): Boolean {
        val remoteId = row.remoteId ?: return false
        val item = try {
            remote.getItem(remoteId)
        } catch (e: GraphException) {
            if (e.isNotFound()) return false else throw e
        }
        val remoteItem = item
        val fs = localFsFor(pair)
        val view = folderView(store.entries(pair.id), mapOf(row.relPath to remoteItem))
        val plan = Plan(listOf(Action.Download(row.relPath, remoteId, remoteItem.size)), emptyList(), emptyList())
        return runResolution(plan, pair, fs, view)
    }

    /** Moves the local copy aside (keeping it indexed for the next upload) and takes the remote one. */
    private suspend fun keepBoth(pair: Pair, row: ConflictRow): Boolean {
        val remoteId = row.remoteId ?: return false
        val item = try {
            remote.getItem(remoteId)
        } catch (e: GraphException) {
            if (e.isNotFound()) return false else throw e
        }
        val fs = localFsFor(pair)
        val copyRel = freeConflictPath(fs, row.relPath, clock())
        if (!fs.move(row.relPath, copyRel)) return false
        store.deleteEntry(pair.id, row.relPath)
        indexLocalCopy(pair, fs, copyRel)

        val remoteItem = item
        val view = folderView(store.entries(pair.id), mapOf(row.relPath to remoteItem))
        val plan = Plan(listOf(Action.Download(row.relPath, remoteId, remoteItem.size)), emptyList(), emptyList())
        return runResolution(plan, pair, fs, view)
    }

    private suspend fun runResolution(plan: Plan, pair: Pair, fs: LocalFs, view: PairView): Boolean {
        val stats = Executor(remote, fs, store, clock).execute(plan, pair, view) { _, _, _ -> }
        return stats.failed == 0 && stats.done == plan.actions.size
    }

    /**
     * Remote folders the index knows, so the executor can resolve an upload/download parent without
     * a delta view. [extra] adds the item being downloaded under its rel path.
     */
    private fun folderView(entries: List<Entry>, extra: Map<String, RemoteItem>): PairView {
        val items = HashMap<String, RemoteItem>()
        for (entry in entries) {
            val remoteId = entry.remoteId ?: continue
            if (!entry.isDir) continue
            items[entry.relPath] = RemoteItem(
                id = remoteId,
                name = entry.name,
                parentId = null,
                isDir = true,
                size = 0,
                mtimeMs = 0,
                quickXorHash = null,
                deleted = false,
            )
        }
        items.putAll(extra)
        return PairView(items = items, deletedIds = emptySet())
    }

    private fun indexLocalCopy(pair: Pair, fs: LocalFs, relPath: String) {
        val file = fs.file(relPath)
        store.upsertEntry(
            Entry(
                id = 0,
                pairId = pair.id,
                relPath = relPath,
                parentRel = parentOf(relPath).ifEmpty { null },
                name = relPath.substringAfterLast('/'),
                isDir = false,
                size = file.length(),
                mtimeMs = file.lastModified(),
                localHash = fs.hash(relPath),
                remoteId = null,
                remoteEtag = null,
                remoteHash = null,
                syncedAt = clock(),
                state = EntryState.LOCAL_NEW,
            ),
        )
    }

    private fun removeEntries(pairId: Long, relPath: String) {
        val prefix = "$relPath/"
        for (entry in store.entries(pairId)) {
            if (entry.relPath == relPath || entry.relPath.startsWith(prefix)) store.deleteEntry(pairId, entry.relPath)
        }
    }

    private fun persistPending(ops: List<PendingOp>): List<PendingOp> {
        if (ops.isEmpty()) return emptyList()
        val queued = store.listPending()
        val added = ops.filter { op ->
            queued.none { it.pairId == op.pairId && it.kind == op.kind && it.relPath == op.relPath }
        }
        added.forEach { store.addPending(it) }
        return added
    }

    private fun persistConflicts(rows: List<ConflictRow>): List<ConflictRow> {
        if (rows.isEmpty()) return emptyList()
        val queued = store.listConflicts()
        val added = rows.filter { row ->
            queued.none { it.pairId == row.pairId && it.relPath == row.relPath }
        }
        added.forEach { store.addConflict(it) }
        return added
    }

    private fun log(message: String) = store.log(clock(), "WARN", TAG, message)

    private fun log(message: String, level: String) = store.log(clock(), level, TAG, message)

    private fun logInfo(message: String) = store.log(clock(), "INFO", TAG, message)

    private class DeltaFetch(
        val items: List<RemoteItem>,
        val deltaLink: String?,
        val resync: Boolean,
        val complete: Boolean = true,
        val pages: Int = 1,
    )

    private class Page(
        val items: List<RemoteItem>,
        val deltaLink: String?,
        val complete: Boolean = true,
        val pages: Int = 1,
    )

    /** One pair's share of the cycle: actions executed, failures, queued work and transfers. */
    private data class PairStats(
        val done: Int,
        val failed: Int,
        val pending: Int,
        val conflicts: Int,
        val downloads: Int,
        val uploads: Int,
    )

    internal companion object {
        const val TAG = "SyncEngine"
        const val KEY_DELTA_LINK = "delta_link"
        const val MAX_PAGES = 1000
        private const val HASH_PROGRESS_FILES = 50
        private const val HASH_PROGRESS_MS = 250L
    }
}

private fun parentOf(relPath: String): String = relPath.substringBeforeLast('/', "")

private val CONFLICT_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

/** `"<base> (conflito <stamp>)<ext>"` next to the original. */
private fun conflictPath(relPath: String, stamp: String): String {
    val dir = relPath.substringBeforeLast('/', "")
    val name = relPath.substringAfterLast('/')
    val dot = name.lastIndexOf('.')
    val base = if (dot <= 0) name else name.substring(0, dot)
    val ext = if (dot <= 0) "" else name.substring(dot)
    val fileName = "$base (conflito $stamp)$ext"
    return if (dir.isEmpty()) fileName else "$dir/$fileName"
}

private fun freeConflictPath(fs: LocalFs, relPath: String, now: Long): String {
    val stamp = CONFLICT_STAMP.format(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()))
    val first = conflictPath(relPath, stamp)
    if (!fs.exists(first)) return first
    var n = 2
    while (fs.exists(conflictPath(relPath, "$stamp-$n"))) n++
    return conflictPath(relPath, "$stamp-$n")
}
