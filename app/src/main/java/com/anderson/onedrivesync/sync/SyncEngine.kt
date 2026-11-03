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
 * Orchestrates one sync cycle: per pair, delta fetch, local scan, planner, executor, index persistence.
 *
 * One cycle at a time ([Mutex]); [cancel] cancels the job the cycle runs in, so the caller's
 * coroutine sees a normal `CancellationException` and nothing is left half-locked.
 *
 * Each pair owns its delta cursor (`delta_link.<pairId>`) and enumerates its own folder; a pair
 * whose folder delta Graph refuses permanently falls back to the drive root
 * (`delta_root.<pairId>`), filtered by [RemoteTree] like any other pair view.
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
            // With several pairs only the global banner can claim the phase (the name matches no
            // card on purpose); each pair then reports its own name inside [runPair].
            val label = pairs.singleOrNull()?.displayName.orEmpty()
            try {
                _state.value = SyncState.Running(label, "lendo OneDrive", 0, 0, phase = DELTA_PHASE, phases = PHASES)
                for (pair in pairs) {
                    val stats = runPair(pair, onNotify)
                    recorded += pair.id
                    done += stats.done
                    failed += stats.failed
                    pending += stats.pending
                    conflicts += stats.conflicts
                    downloads += stats.downloads
                    uploads += stats.uploads
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

            _state.value = SyncState.Running(name, "lendo OneDrive", 0, 0, phase = DELTA_PHASE, phases = PHASES)
            val delta = pairDelta(pair)

            _state.value = SyncState.Running(name, "lendo pastas locais", 0, 0, phase = SCAN_PHASE, phases = PHASES)
            val scanStart = clock()
            val localFiles = fs.scan()
            logInfo("scan local: ${localFiles.size} entradas em ${clock() - scanStart} ms")

            val view = RemoteTree.resolve(pair.remoteId, prev, delta.items)

            val hashStart = clock()
            val toHash = filesToHash(prev, localFiles, view)
            _state.value =
                SyncState.Running(name, "calculando hashes", 0, toHash.size, phase = HASH_PHASE, phases = PHASES)
            val hashes = hashFiles(toHash, fs) { hashed, total ->
                _state.value =
                    SyncState.Running(name, "calculando hashes", hashed, total, phase = HASH_PHASE, phases = PHASES)
            }
            logInfo("hashes: ${hashes.size}/${toHash.size} em ${clock() - hashStart} ms")

            _state.value = SyncState.Running(name, "planejando", 0, 0, phase = PLAN_PHASE, phases = PHASES)
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
                _state.value = SyncState.Running(name, step, done, total, phase = EXEC_PHASE, phases = PHASES)
            }
            exec = stats

            val newPending = persistPending(pending)
            val newConflicts = persistConflicts(plan.conflicts)
            pendingCount = newPending.size
            conflictCount = newConflicts.size
            // The cursor is saved only after the whole pair finished cleanly: advancing it after a
            // failed action would skip that item forever. Replaying the same window is safe.
            if (stats.failed == 0) {
                delta.deltaLink?.let { store.setState(deltaKey(pair.id), it) }
                store.touchPair(pair.id, clock())
            }
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

    /**
     * Delta window for one pair. A fresh pair tries the folder scope first; when Graph refuses it
     * (400/404/405/501, or a not-supported/invalid-request code) the pair permanently falls back to
     * the drive root and filters it by ancestry. A 410 in either scope restarts that scope.
     */
    private suspend fun pairDelta(pair: Pair): DeltaFetch {
        val cursor = store.getState(deltaKey(pair.id))
        if (store.getState(deltaRootKey(pair.id)) != null) return deltaFor(pair, rootId = null, cursor = cursor)
        return try {
            deltaFor(pair, rootId = pair.remoteId, cursor = cursor)
        } catch (e: GraphException) {
            if (!e.isFolderDeltaUnsupported()) throw e
            // One WARN per pair, ever: the flag makes every later cycle skip the folder attempt.
            store.setState(deltaRootKey(pair.id), "1")
            store.setState(deltaKey(pair.id), null)
            log(
                "delta por pasta indisponível para ${pair.displayName} (HTTP ${e.status}): " +
                    "usando a raiz do drive",
            )
            deltaFor(pair, rootId = null, cursor = null)
        }
    }

    private suspend fun deltaFor(pair: Pair, rootId: String?, cursor: String?): DeltaFetch {
        val started = clock()
        val page = try {
            pages(rootId, cursor, pair.displayName)
        } catch (e: GraphException) {
            if (!e.isGone()) throw e
            log(
                "delta expirou (410 Gone) para ${pair.displayName}: reenumerando do zero" +
                    (e.code?.let { " (código $it)" } ?: ""),
            )
            store.setState(deltaKey(pair.id), null)
            val fresh = pages(rootId, null, pair.displayName)
            logDelta(pair, rootId, fresh, started)
            return DeltaFetch(fresh.items, fresh.deltaLink, resync = true, pages = fresh.pages)
        }
        logDelta(pair, rootId, page, started)
        // A full enumeration is legitimate, not a 410 resync: tombstones that do arrive must still
        // produce DELETE_* pending ops, so resync stays false on this path.
        return DeltaFetch(page.items, page.deltaLink, resync = false, pages = page.pages)
    }

    private fun logDelta(pair: Pair, rootId: String?, page: Page, started: Long) {
        val scope = if (rootId == null) "raiz do drive" else "pasta ${pair.displayName}"
        logInfo("delta ($scope): ${page.items.size} itens em ${page.pages} página(s) em ${clock() - started} ms")
    }

    private suspend fun pages(rootId: String?, cursor: String?, pairName: String): Page {
        // The same item may be reported more than once in one cycle; the last occurrence wins.
        val items = LinkedHashMap<String, RemoteItem>()
        var next: String? = cursor
        var count = 0
        while (count < MAX_PAGES) {
            val page = remote.deltaPage(rootId, next)
            for (item in page.items) items[item.id] = item
            count++
            // A delta has no real total: the page number and the accumulated item count are the
            // honest progress numbers, and the card shows them while the enumeration runs.
            _state.value = SyncState.Running(
                pairName,
                "lendo OneDrive · página $count · ${items.size} itens",
                count,
                0,
                phase = DELTA_PHASE,
                phases = PHASES,
            )
            if (page.nextLink == null) return Page(items.values.toList(), page.deltaLink, pages = count)
            // One line per page: an enumeration of a big drive takes minutes and must leave a trail
            // in the activity log, which is the only diagnostic the user can send.
            logInfo("delta: página $count, ${items.size} itens até agora")
            next = page.nextLink
        }
        // Planning against a partial drive view would invent uploads for items we simply have not
        // enumerated yet. Nothing is applied and the cursor stays put so the range is retried.
        val message = "delta truncado em $MAX_PAGES páginas: drive maior do que o suportado; " +
            "nenhuma ação foi aplicada neste ciclo"
        log(message, "ERROR")
        throw IllegalStateException(message)
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
        val pages: Int = 1,
    )

    private class Page(
        val items: List<RemoteItem>,
        val deltaLink: String?,
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
        const val MAX_PAGES = 1000

        /** Number of phases a pair reports while it runs; the card shows "Etapa N de 5". */
        const val PHASES = 5
        private const val DELTA_PHASE = 1
        private const val SCAN_PHASE = 2
        private const val HASH_PHASE = 3
        private const val PLAN_PHASE = 4
        private const val EXEC_PHASE = 5

        private const val HASH_PROGRESS_FILES = 50
        private const val HASH_PROGRESS_MS = 250L

        /** Cursor of [pairId]'s delta, in whichever scope it ended up using. */
        fun deltaKey(pairId: Long): String = "delta_link.$pairId"

        /** Present when [pairId] fell back to the drive root delta; permanent for that pair. */
        fun deltaRootKey(pairId: Long): String = "delta_root.$pairId"
    }
}

private fun parentOf(relPath: String): String = relPath.substringBeforeLast('/', "")

/** Graph refuses a folder delta with any of these: the pair must use the drive root instead. */
private fun GraphException.isFolderDeltaUnsupported(): Boolean =
    status == 400 || status == 404 || status == 405 || status == 501 ||
        code?.contains("notSupported", ignoreCase = true) == true ||
        code?.contains("invalidRequest", ignoreCase = true) == true

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
