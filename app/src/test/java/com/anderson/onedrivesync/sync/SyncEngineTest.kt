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
        const val NOW = 1_700_000_000_000L
    }
}
