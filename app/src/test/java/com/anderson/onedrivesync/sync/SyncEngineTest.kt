package com.anderson.onedrivesync.sync

import com.anderson.onedrivesync.db.EntryState
import com.anderson.onedrivesync.db.Pair
import com.anderson.onedrivesync.db.PendingKind
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SyncEngineTest {
    private lateinit var root: File
    private lateinit var remote: FakeRemoteApi
    private lateinit var store: FakeIndexStore
    private lateinit var fs: FileLocalFs
    private lateinit var engine: SyncEngine

    @Before
    fun setUp() {
        root = Files.createTempDirectory("engine").toFile()
        remote = FakeRemoteApi()
        store = FakeIndexStore(listOf(pair()))
        fs = FileLocalFs(root)
        engine = SyncEngine(store, remote, { fs }, { NOW })
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `first sync downloads remote files and creates remote folders`() {
        remote.addFolder("Docs")
        remote.addFile("Docs/a.txt", "alfa", mtimeMs = 1_600_000_000_000)
        remote.addFile("b.txt", "beta", mtimeMs = 1_600_000_001_000)

        runSync()

        assertTrue(File(root, "Docs").isDirectory)
        assertEquals("alfa", File(root, "Docs/a.txt").readText())
        assertEquals("beta", File(root, "b.txt").readText())
        assertEquals(1_600_000_000_000L, File(root, "Docs/a.txt").lastModified())
        assertEquals(
            setOf("Docs", "Docs/a.txt", "b.txt"),
            store.entriesOf(PAIR).map { it.relPath }.toSet(),
        )
        assertTrue(store.entriesOf(PAIR).all { it.state == EntryState.SYNCED })
    }

    @Test
    fun `uploads a new local file`() {
        runSync()
        File(root, "novo.txt").writeText("olá")
        remote.clearCalls()

        runSync()

        val upload = remote.calls.filterIsInstance<FakeRemoteApi.Call.Upload>().single()
        assertEquals(remote.rootId, upload.parentId)
        assertEquals("novo.txt", upload.name)
        assertEquals("olá", remote.textOf("novo.txt"))
        assertEquals(1, remote.calls.count { it is FakeRemoteApi.Call.SetMtime })
        assertEquals(1, store.entriesOf(PAIR).count { it.relPath == "novo.txt" })
    }

    @Test
    fun `remote rename moves the local file without downloading`() {
        remote.addFile("a.txt", "conteudo")
        runSync()
        remote.clearCalls()

        remote.rename(remote.idOf("a.txt"), remote.rootId, "b.txt")
        runSync()

        assertTrue(File(root, "b.txt").isFile)
        assertFalse(File(root, "a.txt").exists())
        assertEquals("conteudo", File(root, "b.txt").readText())
        assertEquals(0, remote.calls.count { it is FakeRemoteApi.Call.Download })
        assertEquals(setOf("b.txt"), store.entriesOf(PAIR).map { it.relPath }.toSet())
    }

    @Test
    fun `local rename moves the remote file`() {
        remote.addFile("a.txt", "conteudo")
        runSync()
        remote.clearCalls()

        assertTrue(fs.move("a.txt", "b.txt"))
        runSync()

        val move = remote.calls.filterIsInstance<FakeRemoteApi.Call.Move>().single()
        assertEquals(remote.rootId, move.parentId)
        assertEquals("b.txt", move.name)
        assertEquals(0, remote.calls.count { it is FakeRemoteApi.Call.Upload })
        assertEquals("conteudo", remote.textOf("b.txt"))
    }

    @Test
    fun `both sides changed creates a conflict and leaves the local file alone`() {
        remote.addFile("a.txt", "remoto")
        runSync()
        File(root, "a.txt").writeText("local!!")
        remote.addFile("a.txt", "remoto alterado")
        remote.clearCalls()

        runSync()

        val conflict = store.conflictRows.single()
        assertEquals("a.txt", conflict.relPath)
        assertEquals("local!!", File(root, "a.txt").readText())
        assertEquals(0, remote.calls.count { it is FakeRemoteApi.Call.Download })
        assertEquals(0, remote.calls.count { it is FakeRemoteApi.Call.Upload })
    }

    @Test
    fun `remote deletion queues a local delete and keeps the file`() {
        remote.addFile("a.txt", "x")
        runSync()
        remote.clearCalls()

        remote.remove(remote.idOf("a.txt"))
        runSync()

        val op = store.pendingOps.single()
        assertEquals(PendingKind.DELETE_LOCAL, op.kind)
        assertEquals("a.txt", op.relPath)
        assertTrue(File(root, "a.txt").isFile)
        assertEquals(0, remote.calls.count { it is FakeRemoteApi.Call.Download })
    }

    @Test
    fun `local deletion queues a remote delete and keeps the remote file`() {
        remote.addFile("a.txt", "x")
        runSync()
        remote.clearCalls()

        assertTrue(File(root, "a.txt").delete())
        runSync()

        val op = store.pendingOps.single()
        assertEquals(PendingKind.DELETE_REMOTE, op.kind)
        assertEquals("a.txt", op.relPath)
        assertTrue(remote.has("a.txt"))
        assertEquals(0, remote.calls.count { it is FakeRemoteApi.Call.Delete })
    }

    @Test
    fun `a second run without changes transfers nothing`() {
        remote.addFolder("Docs")
        remote.addFile("Docs/a.txt", "alfa")
        remote.addFile("b.txt", "beta")
        runSync()
        remote.clearCalls()

        runSync()

        assertEquals(0, remote.calls.count { it is FakeRemoteApi.Call.Download })
        assertEquals(0, remote.calls.count { it is FakeRemoteApi.Call.Upload })
        assertEquals(0, remote.calls.count { it is FakeRemoteApi.Call.CreateFolder })
        assertTrue(store.pendingOps.isEmpty())
        assertTrue(store.conflictRows.isEmpty())
    }

    @Test
    fun `a remote hash in base64 still downloads and the next cycle transfers nothing`() {
        remote.hashesAsBase64 = true
        remote.addFile("a.txt", "{}\n", mtimeMs = 1_600_000_000_000)

        runSync()

        assertEquals("{}\n", File(root, "a.txt").readText())
        val entry = store.entriesOf(PAIR).single()
        assertEquals("a.txt", entry.relPath)
        assertEquals("7be8830200000000000000000300000000000000", entry.remoteHash)
        assertEquals(hashBytes("{}\n".toByteArray()), entry.localHash)
        assertTrue(store.conflictRows.isEmpty())
        assertNotNull(store.stateOf(SyncEngine.deltaKey(PAIR)))
        remote.clearCalls()

        runSync()

        assertEquals(0, remote.calls.count { it is FakeRemoteApi.Call.Download })
        assertEquals(0, remote.calls.count { it is FakeRemoteApi.Call.Upload })

        // Force a full enumeration so the second pass really compares the stored hash with the remote one.
        store.setState(SyncEngine.deltaKey(PAIR), null)
        remote.clearCalls()
        runSync()

        assertEquals(0, remote.calls.count { it is FakeRemoteApi.Call.Download })
        assertEquals(0, remote.calls.count { it is FakeRemoteApi.Call.Upload })
        assertTrue(store.conflictRows.isEmpty())
        assertTrue(store.pendingOps.isEmpty())
    }

    @Test
    fun `expired delta reenumerates and creates no deletion pending`() {
        remote.addFile("a.txt", "x")
        runSync()
        assertTrue(File(root, "a.txt").delete())
        remote.clearCalls()
        remote.failNextDeltaWithGone = true

        runSync()

        val deltas = remote.calls.filterIsInstance<FakeRemoteApi.Call.Delta>().map { it.cursor }
        assertEquals(2, deltas.size)
        assertNull(deltas.last())
        assertTrue(deltas.first() != null)
        assertTrue(store.pendingOps.isEmpty())
        assertTrue(store.entriesOf(PAIR).any { it.relPath == "a.txt" })
        assertFalse(File(root, "a.txt").exists())
    }

    @Test
    fun `approving a local delete removes the file and its entry`() {
        remote.addFile("a.txt", "x")
        runSync()
        remote.remove(remote.idOf("a.txt"))
        runSync()
        val op = store.pendingOps.single()

        runBlocking { engine.approvePending(op.id) }

        assertFalse(File(root, "a.txt").exists())
        assertTrue(store.entriesOf(PAIR).isEmpty())
        assertTrue(store.pendingOps.isEmpty())
    }

    @Test
    fun `approving a remote delete removes the remote item and its entry`() {
        remote.addFile("b.txt", "y")
        runSync()
        assertTrue(File(root, "b.txt").delete())
        runSync()
        val op = store.pendingOps.single()
        remote.clearCalls()

        runBlocking { engine.approvePending(op.id) }

        assertFalse(remote.has("b.txt"))
        assertEquals(1, remote.calls.count { it is FakeRemoteApi.Call.Delete })
        assertTrue(store.entriesOf(PAIR).isEmpty())
        assertTrue(store.pendingOps.isEmpty())
    }

    @Test
    fun `rejecting a pending delete keeps both sides`() {
        remote.addFile("a.txt", "x")
        runSync()
        remote.remove(remote.idOf("a.txt"))
        runSync()
        val op = store.pendingOps.single()

        runBlocking { engine.rejectPending(op.id) }

        assertTrue(store.pendingOps.isEmpty())
        assertTrue(store.entriesOf(PAIR).any { it.relPath == "a.txt" })
    }

    @Test
    fun `keep local uploads over the remote copy`() {
        val id = createConflict()
        remote.clearCalls()

        runBlocking { engine.resolveConflict(id, ConflictDecision.KEEP_LOCAL) }

        assertEquals("local!!", remote.textOf("a.txt"))
        assertEquals(1, remote.calls.count { it is FakeRemoteApi.Call.Upload })
        assertTrue(store.conflictRows.isEmpty())
        assertEquals(hashBytes("local!!".toByteArray()), store.entriesOf(PAIR).single { it.relPath == "a.txt" }.localHash)
    }

    @Test
    fun `keep remote downloads over the local copy`() {
        val id = createConflict()
        remote.clearCalls()

        runBlocking { engine.resolveConflict(id, ConflictDecision.KEEP_REMOTE) }

        assertEquals("remoto alterado", File(root, "a.txt").readText())
        assertEquals(1, remote.calls.count { it is FakeRemoteApi.Call.Download })
        assertTrue(store.conflictRows.isEmpty())
    }

    @Test
    fun `keep both renames the local copy and downloads the remote one`() {
        val id = createConflict()
        remote.clearCalls()

        runBlocking { engine.resolveConflict(id, ConflictDecision.KEEP_BOTH) }

        assertEquals("remoto alterado", File(root, "a.txt").readText())
        val copy = checkNotNull(root.listFiles()).single { it.name.startsWith("a (conflito ") && it.extension == "txt" }
        assertEquals("local!!", copy.readText())
        assertEquals(1, remote.calls.count { it is FakeRemoteApi.Call.Download })

        val copied = store.entriesOf(PAIR).single { it.relPath == copy.name }
        assertNull(copied.remoteId)
        assertEquals(EntryState.LOCAL_NEW, copied.state)
        assertTrue(store.conflictRows.isEmpty())
    }

    @Test
    fun `creates the remote folder before uploading its contents`() {
        File(root, "Fotos").mkdirs()
        File(root, "Fotos/x.txt").writeText("foto")

        runSync()

        val folderId = remote.idOf("Fotos")
        val upload = remote.calls.filterIsInstance<FakeRemoteApi.Call.Upload>().single()
        assertEquals(folderId, upload.parentId)
        assertEquals("x.txt", upload.name)
        val folderCall = remote.calls.indexOfFirst { it is FakeRemoteApi.Call.CreateFolder }
        val uploadCall = remote.calls.indexOfFirst { it is FakeRemoteApi.Call.Upload }
        assertTrue(folderCall in 0 until uploadCall)
        assertEquals("foto", remote.textOf("Fotos/x.txt"))
    }

    @Test
    fun `syncAll downloads each pair from its own folder delta`() {
        val two = twoPairs()

        two.syncAll()

        assertEquals("alfa", File(two.localA, "a.txt").readText())
        assertEquals("beta", File(two.localB, "b.txt").readText())
        val deltas = two.remote.calls.filterIsInstance<FakeRemoteApi.Call.Delta>()
        assertEquals(2, deltas.size)
        assertTrue(deltas.all { it.rootId != null })
        assertEquals(2, deltas.mapNotNull { it.rootId }.toSet().size)
    }

    @Test
    fun `a remote change in the second pair is not swallowed by the other pair cursor`() {
        val two = twoPairs()
        two.syncAll()
        two.remote.clearCalls()

        two.remote.addFile("B/b.txt", "beta 2", mtimeMs = 1_700_000_000_000)

        two.syncAll()

        assertEquals("beta 2", File(two.localB, "b.txt").readText())
        assertEquals(listOf("b.txt"), two.remote.downloads())
        assertEquals("alfa", File(two.localA, "a.txt").readText())
    }

    @Test
    fun `a pair created after the first sync still downloads its files`() {
        val remote = FakeRemoteApi()
        val localA = File(root, "a").apply { mkdirs() }
        val localB = File(root, "b").apply { mkdirs() }
        val remoteA = remote.addFolder("A")
        val remoteB = remote.addFolder("B")
        remote.addFile("A/a.txt", "alfa")
        remote.addFile("B/b.txt", "beta")
        val store = FakeIndexStore(listOf(pairOf(PAIR, remoteA, localA)))
        val engine = engineFor(store, remote)

        runBlocking { engine.syncAll() }
        assertFalse(File(localB, "b.txt").exists())

        store.addPair(pairOf(PAIR_B, remoteB, localB))
        remote.clearCalls()
        runBlocking { engine.syncAll() }

        assertEquals("beta", File(localB, "b.txt").readText())
        assertEquals(listOf("b.txt"), remote.downloads())
        // The new pair has no cursor, so it enumerates its folder from scratch; the old one replays.
        val deltas = remote.calls.filterIsInstance<FakeRemoteApi.Call.Delta>()
        assertEquals(2, deltas.size)
        assertNull(deltas.single { it.rootId == remoteB }.cursor)
        assertNotNull(deltas.single { it.rootId == remoteA }.cursor)
    }

    @Test
    fun `a first clean cycle saves each pair cursor and the next cycle transfers nothing`() {
        val two = twoPairs()

        two.syncAll()
        assertNotNull(two.store.stateOf(SyncEngine.deltaKey(PAIR)))
        assertNotNull(two.store.stateOf(SyncEngine.deltaKey(PAIR_B)))

        two.remote.clearCalls()
        two.syncAll()

        assertEquals(0, two.remote.downloads().size)
    }

    @Test
    fun `a pair on a folder enumerates only that folder`() {
        remote.addFolder("Docs")
        remote.addFolder("Docs/Sub")
        remote.addFile("Docs/Sub/a.txt", "alfa")
        remote.addFolder("Outros")
        remote.addFile("Outros/b.txt", "beta")
        val local = File(root, "docs").apply { mkdirs() }
        val folderId = remote.idOf("Docs")
        val store = FakeIndexStore(listOf(pairOf(PAIR, folderId, local)))
        val engine = engineFor(store, remote)

        runBlocking { engine.syncAll() }

        val deltas = remote.calls.filterIsInstance<FakeRemoteApi.Call.Delta>()
        assertTrue(deltas.isNotEmpty())
        assertTrue(deltas.all { it.rootId == folderId })
        assertEquals("alfa", File(local, "Sub/a.txt").readText())
        assertFalse(File(local, "b.txt").exists())
        assertEquals(setOf("Sub", "Sub/a.txt"), store.entriesOf(PAIR).map { it.relPath }.toSet())
    }

    @Test
    fun `a new folder pair stores its cursor and the next cycle does not reenumerate`() {
        remote.addFolder("Docs")
        remote.addFile("Docs/a.txt", "alfa")
        val local = File(root, "docs").apply { mkdirs() }
        val store = FakeIndexStore(listOf(pairOf(PAIR, remote.idOf("Docs"), local)))
        val engine = engineFor(store, remote)

        runBlocking { engine.syncAll() }
        assertNotNull(store.stateOf(SyncEngine.deltaKey(PAIR)))
        remote.clearCalls()

        runBlocking { engine.syncAll() }

        assertEquals(0, remote.downloads().size)
        val cursors = remote.deltaCursors()
        assertTrue(cursors.size <= 1)
        assertTrue(cursors.none { it == null })
    }

    @Test
    fun `folder delta unsupported falls back to the root and is remembered`() {
        remote.folderDeltaUnsupported = true
        remote.addFolder("Docs")
        remote.addFile("Docs/a.txt", "alfa")
        val local = File(root, "docs").apply { mkdirs() }
        val store = FakeIndexStore(listOf(pairOf(PAIR, remote.idOf("Docs"), local)))
        val engine = engineFor(store, remote)

        runBlocking { engine.syncAll() }

        assertEquals("alfa", File(local, "a.txt").readText())
        assertEquals("1", store.stateOf(SyncEngine.deltaRootKey(PAIR)))
        val warn = store.logs.single { it.startsWith("WARN") && it.contains("delta por pasta indisponível") }
        assertTrue(warn.contains("HTTP 400"))
        assertTrue(warn.contains("Teste $PAIR"))
        assertEquals(
            listOf(false, true),
            remote.calls.filterIsInstance<FakeRemoteApi.Call.Delta>().map { it.rootId == null },
        )

        remote.clearCalls()
        runBlocking { engine.syncAll() }

        val deltas = remote.calls.filterIsInstance<FakeRemoteApi.Call.Delta>()
        assertEquals(1, deltas.size)
        assertNull(deltas.single().rootId)
        assertEquals(1, store.logs.count { it.contains("delta por pasta indisponível") })
    }

    @Test
    fun `410 on the folder delta clears the cursor and reenumerates with resync`() {
        remote.addFolder("Docs")
        remote.addFile("Docs/a.txt", "x")
        val local = File(root, "docs").apply { mkdirs() }
        val store = FakeIndexStore(listOf(pairOf(PAIR, remote.idOf("Docs"), local)))
        val engine = engineFor(store, remote)
        runBlocking { engine.syncAll() }
        assertTrue(File(local, "a.txt").delete())
        remote.clearCalls()
        remote.failNextDeltaWithGone = true

        runBlocking { engine.syncAll() }

        val cursors = remote.deltaCursors()
        assertEquals(2, cursors.size)
        assertNotNull(cursors.first())
        assertNull(cursors.last())
        assertTrue(store.pendingOps.isEmpty())
        assertTrue(store.entriesOf(PAIR).any { it.relPath == "a.txt" })
        assertFalse(File(local, "a.txt").exists())
        assertNotNull(store.stateOf(SyncEngine.deltaKey(PAIR)))
    }

    @Test
    fun `each pair keeps its own cursor and one does not invalidate the other`() {
        val two = twoPairs()
        two.syncAll()
        assertNotNull(two.store.stateOf(SyncEngine.deltaKey(PAIR)))
        assertNotNull(two.store.stateOf(SyncEngine.deltaKey(PAIR_B)))

        // Simulate a pair that needs a full re-enumeration; the other must keep replaying its cursor.
        two.store.setState(SyncEngine.deltaKey(PAIR), null)
        two.remote.clearCalls()
        two.syncAll()

        val deltas = two.remote.calls.filterIsInstance<FakeRemoteApi.Call.Delta>()
        assertEquals(2, deltas.size)
        assertEquals(1, deltas.count { it.cursor == null })
        assertEquals(1, deltas.count { it.cursor != null })
        assertNotNull(two.store.stateOf(SyncEngine.deltaKey(PAIR_B)))

        two.remote.addFile("B/b.txt", "beta 2", mtimeMs = 1_700_000_000_000)
        two.remote.clearCalls()
        two.syncAll()

        assertEquals(listOf("b.txt"), two.remote.downloads())
    }

    @Test
    fun `a remote deletion still queues a local delete after a full re-enumeration`() {
        val two = twoPairs()
        two.syncAll()
        two.remote.remove(two.remote.idOf("B/b.txt"))
        two.remote.clearCalls()

        two.syncAll()

        val op = two.store.pendingOps.single { it.pairId == PAIR_B }
        assertEquals(PendingKind.DELETE_LOCAL, op.kind)
        assertEquals("b.txt", op.relPath)
        assertTrue(File(two.localB, "b.txt").isFile)
    }

    @Test
    fun `a failed download keeps the delta cursor and is retried on the next cycle`() {
        remote.addFile("a.txt", "conteudo")
        remote.failNextDownload(remote.idOf("a.txt"))

        runSync()

        assertNull(store.stateOf(SyncEngine.deltaKey(PAIR)))
        assertFalse(File(root, "a.txt").exists())
        assertTrue(store.entriesOf(PAIR).isEmpty())

        runSync()

        assertEquals("conteudo", File(root, "a.txt").readText())
        assertNotNull(store.stateOf(SyncEngine.deltaKey(PAIR)))
    }

    @Test
    fun `a failed first cycle leaves the new pair unsynced and the next one enumerates fully`() {
        remote.addFolder("A")
        remote.addFile("A/a.txt", "alfa")
        val local = File(root, "a").apply { mkdirs() }
        val store = FakeIndexStore(listOf(pairOf(PAIR, remote.idOf("A"), local)))
        val engine = engineFor(store, remote)
        remote.failNextDownload(remote.idOf("A/a.txt"))

        runBlocking { engine.syncAll() }

        assertNull(store.getPair(PAIR)?.lastSyncAt)
        assertNull(store.stateOf(SyncEngine.deltaKey(PAIR)))
        assertFalse(File(local, "a.txt").exists())

        runBlocking { engine.syncAll() }

        assertEquals(listOf<String?>(null, null), remote.deltaCursors())
        assertEquals("alfa", File(local, "a.txt").readText())
        assertNotNull(store.getPair(PAIR)?.lastSyncAt)
    }

    @Test
    fun `a corrupted download leaves no entry or file and is retried`() {
        remote.addFile("a.txt", "conteudo")
        remote.corruptNextDownload(remote.idOf("a.txt"))

        runSync()

        assertNull(store.stateOf(SyncEngine.deltaKey(PAIR)))
        assertFalse(File(root, "a.txt").exists())
        assertTrue(store.entriesOf(PAIR).isEmpty())

        runSync()

        assertEquals("conteudo", File(root, "a.txt").readText())
        assertEquals(hashBytes("conteudo".toByteArray()), store.entriesOf(PAIR).single().localHash)
        assertEquals(0, remote.calls.count { it is FakeRemoteApi.Call.Upload })
    }

    @Test
    fun `a truncated download is rejected by size and is retried`() {
        remote.addFile("a.txt", "conteudo")
        remote.truncateNextDownload(remote.idOf("a.txt"))

        runSync()

        assertNull(store.stateOf(SyncEngine.deltaKey(PAIR)))
        assertFalse(File(root, "a.txt").exists())
        assertTrue(store.entriesOf(PAIR).isEmpty())

        runSync()

        assertEquals("conteudo", File(root, "a.txt").readText())
    }

    @Test
    fun `create folder that already exists remotely is adopted and is not a failure`() {
        val folderId = remote.addFolder("Docs")
        remote.failNextCreateFolder()
        val plan = Plan(listOf(Action.CreateFolderRemote("Docs")), emptyList(), emptyList())

        val stats = runBlocking {
            val item = remote.getItem(folderId)
            val view = PairView(items = mapOf("Docs" to item), deletedIds = emptySet())
            executor().execute(plan, pair(), view) { _, _, _ -> }
        }

        assertEquals(0, stats.failed)
        assertEquals(1, stats.done)
        assertEquals(folderId, store.entriesOf(PAIR).single { it.relPath == "Docs" }.remoteId)
    }

    @Test
    fun `move local whose source was already renamed only rewrites the index`() {
        remote.addFile("a.txt", "conteudo")
        runSync()
        val indexed = store.entriesOf(PAIR).single { it.relPath == "a.txt" }
        assertTrue(fs.move("a.txt", "b.txt")) // crash window: rename done, index not rewritten
        val plan = Plan(listOf(Action.MoveLocal("a.txt", "b.txt")), emptyList(), emptyList())

        val stats = runBlocking {
            executor().execute(plan, pair(), PairView(emptyMap(), emptySet())) { _, _, _ -> }
        }

        assertEquals(0, stats.failed)
        val moved = store.entriesOf(PAIR).single()
        assertEquals("b.txt", moved.relPath)
        assertEquals(indexed.remoteId, moved.remoteId)
        assertTrue(File(root, "b.txt").isFile)
    }

    @Test
    fun `a healthy cycle advances the delta cursor`() {
        remote.addFile("a.txt", "x")
        runSync()
        val first = store.stateOf(SyncEngine.deltaKey(PAIR))
        assertNotNull(first)

        remote.addFile("b.txt", "y")
        runSync()

        val second = store.stateOf(SyncEngine.deltaKey(PAIR))
        assertNotNull(second)
        assertNotEquals(first, second)
        assertEquals("y", File(root, "b.txt").readText())
    }

    @Test
    fun `a successful cycle stores a per pair and a global result`() {
        remote.addFile("a.txt", "conteudo")

        runSync()

        val pairResult = checkNotNull(decodeCycleResult(store.stateOf(CycleResultKeys.pair(PAIR))))
        assertTrue(pairResult.ok)
        assertEquals(1, pairResult.downloaded)
        assertEquals(0, pairResult.uploaded)
        assertEquals(0, pairResult.failed)
        assertEquals(NOW, pairResult.finishedAt)
        assertNull(pairResult.error)

        val global = checkNotNull(decodeCycleResult(store.stateOf(CycleResultKeys.GLOBAL)))
        assertTrue(global.ok)
        assertEquals(1, global.downloaded)
        assertEquals(0, global.uploaded)
        assertEquals(0, global.failed)
    }

    @Test
    fun `an upload cycle counts only uploads in the result`() {
        runSync()
        File(root, "novo.txt").writeText("olá")

        runSync()

        val result = checkNotNull(decodeCycleResult(store.stateOf(CycleResultKeys.pair(PAIR))))
        assertTrue(result.ok)
        assertEquals(0, result.downloaded)
        assertEquals(1, result.uploaded)
        assertEquals(0, result.failed)
    }

    @Test
    fun `a cycle with a failed download stores a failed result`() {
        remote.addFile("a.txt", "conteudo")
        remote.failNextDownload(remote.idOf("a.txt"))

        runSync()

        val pairResult = checkNotNull(decodeCycleResult(store.stateOf(CycleResultKeys.pair(PAIR))))
        assertFalse(pairResult.ok)
        assertTrue(pairResult.failed >= 1)
        assertEquals(0, pairResult.downloaded)

        val global = checkNotNull(decodeCycleResult(store.stateOf(CycleResultKeys.GLOBAL)))
        assertFalse(global.ok)
        assertTrue(global.failed >= 1)
    }

    @Test
    fun `a new local file with no remote counterpart is uploaded without a planning hash`() {
        File(root, "foto.jpg").writeText("foto")
        File(root, "video.mp4").writeText("video")
        val counting = CountingLocalFs(fs, remote)

        runBlocking { SyncEngine(store, remote, { counting }, { NOW }).syncPair(PAIR) }

        assertEquals(0, counting.hashesBeforeFirstUpload)
        assertEquals(
            setOf("foto.jpg", "video.mp4"),
            remote.calls.filterIsInstance<FakeRemoteApi.Call.Upload>().map { it.name }.toSet(),
        )
    }

    @Test
    fun `a local file whose size matches the remote one is hashed exactly once`() {
        remote.addFile("a.txt", "conteudo")
        File(root, "a.txt").writeText("conteudo")
        val counting = CountingLocalFs(fs, remote)

        runBlocking { SyncEngine(store, remote, { counting }, { NOW }).syncPair(PAIR) }

        assertEquals(1, counting.hashCalls)
        assertEquals(0, remote.calls.count { it is FakeRemoteApi.Call.Upload })
        assertEquals(0, remote.calls.count { it is FakeRemoteApi.Call.Download })
    }

    @Test
    fun `a local touch with the same size triggers one planning hash`() {
        remote.addFile("a.txt", "conteudo")
        runSync()
        val file = File(root, "a.txt")
        assertTrue(file.setLastModified(file.lastModified() + 2_000))
        val counting = CountingLocalFs(fs, remote)
        remote.clearCalls()

        runBlocking { SyncEngine(store, remote, { counting }, { NOW }).syncPair(PAIR) }

        assertEquals(1, counting.hashesBeforeFirstUpload)
    }

    /** Two pairs over two distinct remote folders and local directories. */
    private class TwoPairs(
        val remote: FakeRemoteApi,
        val store: FakeIndexStore,
        val engine: SyncEngine,
        val localA: File,
        val localB: File,
    ) {
        fun syncAll() = runBlocking { engine.syncAll() }
    }

    private fun twoPairs(): TwoPairs {
        val remote = FakeRemoteApi()
        val localA = File(root, "a").apply { mkdirs() }
        val localB = File(root, "b").apply { mkdirs() }
        val remoteA = remote.addFolder("A")
        val remoteB = remote.addFolder("B")
        remote.addFile("A/a.txt", "alfa")
        remote.addFile("B/b.txt", "beta")
        val store = FakeIndexStore(listOf(pairOf(PAIR, remoteA, localA), pairOf(PAIR_B, remoteB, localB)))
        return TwoPairs(remote, store, engineFor(store, remote), localA, localB)
    }

    private fun engineFor(store: FakeIndexStore, remote: FakeRemoteApi): SyncEngine =
        SyncEngine(store, remote, { pair -> FileLocalFs(File(pair.localPath)) }, { NOW })

    private fun executor(): Executor = Executor(remote, fs, store) { NOW }

    private fun pairOf(id: Long, remoteId: String, localDir: File): Pair = Pair(
        id = id,
        remoteId = remoteId,
        remotePath = "/$id",
        localPath = localDir.absolutePath,
        displayName = "Teste $id",
        enabled = true,
        createdAt = 0,
        lastSyncAt = null,
    )

    private fun FakeRemoteApi.downloads(): List<String> =
        calls.filterIsInstance<FakeRemoteApi.Call.Download>().map { it.name.removeSuffix(PART_SUFFIX) }

    private fun FakeRemoteApi.deltaCursors(): List<String?> =
        calls.filterIsInstance<FakeRemoteApi.Call.Delta>().map { it.cursor }

    /** Syncs an equal path on both sides, then changes both: one unresolved conflict row. */
    private fun createConflict(): Long {
        remote.addFile("a.txt", "remoto")
        runSync()
        File(root, "a.txt").writeText("local!!")
        remote.addFile("a.txt", "remoto alterado")
        runSync()
        return store.conflictRows.single().id
    }

    private fun runSync() {
        runBlocking { engine.syncPair(PAIR) }
    }

    private fun pair(): Pair = Pair(
        id = PAIR,
        remoteId = FakeRemoteApi.ROOT,
        remotePath = "/",
        localPath = root.absolutePath,
        displayName = "Teste",
        enabled = true,
        createdAt = 0,
        lastSyncAt = null,
    )

    private companion object {
        const val PAIR = 7L
        const val PAIR_B = 8L
        const val NOW = 1_700_000_000_000L
        const val PART_SUFFIX = ".part"
    }
}

/** Counts [LocalFs.hash] calls and whether they happened before the first upload reached the fake. */
private class CountingLocalFs(
    private val delegate: LocalFs,
    private val remote: FakeRemoteApi,
) : LocalFs by delegate {
    var hashCalls = 0
        private set

    var hashesBeforeFirstUpload = 0
        private set

    override fun hash(relPath: String): String {
        hashCalls++
        if (remote.calls.none { it is FakeRemoteApi.Call.Upload }) hashesBeforeFirstUpload++
        return delegate.hash(relPath)
    }
}
