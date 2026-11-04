package com.anderson.onedrivesync.sync

import com.anderson.onedrivesync.db.Entry
import com.anderson.onedrivesync.db.EntryState
import com.anderson.onedrivesync.db.LocalFile
import com.anderson.onedrivesync.db.PendingKind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerTest {
    @Test
    fun `never plans an action for a hidden path`() {
        val plan = plan(
            prev = listOf(entry("pasta/.oculto", "r1")),
            view = viewOf(remote("r2", ".gitignore")),
            local = listOf(local(".nomedia"), local("pasta/.oculto")),
            hashes = mapOf(".nomedia" to HASH_A, "pasta/.oculto" to HASH_A),
        )

        assertTrue(plan.actions.isEmpty())
        assertTrue(plan.pending.isEmpty())
        assertTrue(plan.conflicts.isEmpty())
    }

    @Test
    fun `downloads a new remote file`() {
        val plan = plan(view = viewOf(remote("r1", "a.txt", size = 5, quickXorHash = HASH_A)))

        assertEquals(listOf<Action>(Action.Download("a.txt", "r1", 5L)), plan.actions)
        assertTrue(plan.pending.isEmpty())
        assertTrue(plan.conflicts.isEmpty())
    }

    @Test
    fun `does nothing when a first seen remote file already matches locally`() {
        val plan = plan(
            view = viewOf(remote("r1", "a.txt", size = 5, quickXorHash = HASH_A)),
            local = listOf(local("a.txt", size = 5, mtimeMs = 100)),
            hashes = mapOf("a.txt" to HASH_A),
        )

        assertTrue(plan.actions.isEmpty())
        assertTrue(plan.pending.isEmpty())
        assertTrue(plan.conflicts.isEmpty())
    }

    @Test
    fun `conflicts when a first seen remote file differs locally`() {
        val plan = plan(
            view = viewOf(remote("r1", "a.txt", size = 5, mtimeMs = 1_000, quickXorHash = HASH_A)),
            local = listOf(local("a.txt", size = 5, mtimeMs = 100)),
            hashes = mapOf("a.txt" to HASH_B),
        )

        val conflict = plan.conflicts.single()
        assertEquals("a.txt", conflict.relPath)
        assertEquals("r1", conflict.remoteId)
        assertEquals(5L, conflict.localSize)
        assertEquals(5L, conflict.remoteSize)
        assertEquals(100L, conflict.localMtimeMs)
        assertEquals(1_000L, conflict.remoteMtimeMs)
        assertNull(conflict.decision)
        assertEquals(NOW, conflict.createdAt)
        assertTrue(plan.actions.isEmpty())
    }

    @Test
    fun `downloads when the remote changed and the local copy is intact`() {
        val plan = plan(
            prev = listOf(entry("a.txt", "r1", size = 5, mtimeMs = 100, localHash = HASH_A, remoteHash = HASH_A)),
            view = viewOf(remote("r1", "a.txt", size = 6, mtimeMs = 2_000, quickXorHash = HASH_B)),
            local = listOf(local("a.txt", size = 5, mtimeMs = 100)),
            hashes = mapOf("a.txt" to HASH_A),
        )

        assertEquals(listOf<Action>(Action.Download("a.txt", "r1", 6L)), plan.actions)
    }

    @Test
    fun `uploads when the local changed and the remote copy is intact`() {
        val plan = plan(
            prev = listOf(entry("a.txt", "r1", size = 5, mtimeMs = 100, localHash = HASH_A, remoteHash = HASH_A)),
            view = viewOf(remote("r1", "a.txt", size = 5, mtimeMs = 100, quickXorHash = HASH_A)),
            local = listOf(local("a.txt", size = 6, mtimeMs = 200)),
            hashes = mapOf("a.txt" to HASH_B),
        )

        assertEquals(listOf<Action>(Action.Upload("a.txt", "")), plan.actions)
    }

    @Test
    fun `does nothing when both sides changed to the same content`() {
        val plan = plan(
            prev = listOf(entry("a.txt", "r1", size = 5, mtimeMs = 100, localHash = HASH_A, remoteHash = HASH_A)),
            view = viewOf(remote("r1", "a.txt", size = 6, mtimeMs = 2_000, quickXorHash = HASH_B)),
            local = listOf(local("a.txt", size = 6, mtimeMs = 200)),
            hashes = mapOf("a.txt" to HASH_B),
        )

        assertTrue(plan.actions.isEmpty())
        assertTrue(plan.pending.isEmpty())
        assertTrue(plan.conflicts.isEmpty())
    }

    @Test
    fun `conflicts when both sides changed differently`() {
        val plan = plan(
            prev = listOf(entry("a.txt", "r1", size = 5, mtimeMs = 100, localHash = HASH_A, remoteHash = HASH_A)),
            view = viewOf(remote("r1", "a.txt", size = 6, mtimeMs = 2_000, quickXorHash = HASH_C)),
            local = listOf(local("a.txt", size = 6, mtimeMs = 200)),
            hashes = mapOf("a.txt" to HASH_B),
        )

        assertEquals("a.txt", plan.conflicts.single().relPath)
        assertTrue(plan.actions.isEmpty())
        assertTrue(plan.pending.isEmpty())
    }

    @Test
    fun `queues a local delete when the remote file is gone and the local copy is intact`() {
        val plan = plan(
            prev = listOf(entry("a.txt", "r1", size = 5, mtimeMs = 100, localHash = HASH_A, remoteHash = HASH_A)),
            view = PairView(emptyMap(), setOf("r1")),
            local = listOf(local("a.txt", size = 5, mtimeMs = 100)),
            hashes = mapOf("a.txt" to HASH_A),
        )

        val pending = plan.pending.single()
        assertEquals(PendingKind.DELETE_LOCAL, pending.kind)
        assertEquals("a.txt", pending.relPath)
        assertEquals("r1", pending.remoteId)
        assertEquals("removido no OneDrive", pending.detail)
        assertTrue(plan.actions.isEmpty())
        assertTrue(plan.conflicts.isEmpty())
    }

    @Test
    fun `conflicts when the remote file is gone and the local copy changed`() {
        val plan = plan(
            prev = listOf(entry("a.txt", "r1", size = 5, mtimeMs = 100, localHash = HASH_A, remoteHash = HASH_A)),
            view = PairView(emptyMap(), setOf("r1")),
            local = listOf(local("a.txt", size = 6, mtimeMs = 200)),
            hashes = mapOf("a.txt" to HASH_B),
        )

        assertEquals("a.txt", plan.conflicts.single().relPath)
        assertTrue(plan.actions.isEmpty())
        assertTrue(plan.pending.isEmpty())
    }

    @Test
    fun `queues a remote delete when the local file is gone and the remote is intact`() {
        val plan = plan(
            prev = listOf(entry("a.txt", "r1", size = 5, mtimeMs = 100, localHash = HASH_A, remoteHash = HASH_A)),
        )

        val pending = plan.pending.single()
        assertEquals(PendingKind.DELETE_REMOTE, pending.kind)
        assertEquals("a.txt", pending.relPath)
        assertEquals("r1", pending.remoteId)
        assertEquals("removido localmente", pending.detail)
        assertTrue(plan.actions.isEmpty())
        assertTrue(plan.conflicts.isEmpty())
    }

    @Test
    fun `creates a local folder for a new remote folder`() {
        val plan = plan(view = viewOf(remote("f1", "Fotos", isDir = true)))

        assertEquals(listOf<Action>(Action.CreateFolderLocal("Fotos")), plan.actions)
    }

    @Test
    fun `a remote folder without a file facet and no previous entry becomes a local folder`() {
        val plan = plan(
            view = viewOf(remote("f1", "Aniversário Bernardo", isDir = true, size = 42)),
        )

        assertEquals(listOf<Action>(Action.CreateFolderLocal("Aniversário Bernardo")), plan.actions)
        assertTrue(plan.actions.none { it is Action.Download })
    }

    @Test
    fun `a remote folder reported with a new size and no local copy is created instead of downloaded`() {
        // Graph reports a folder's size as the total of its children: the indexed size (0) always
        // differs, which used to plan `Download` for a directory.
        val plan = plan(
            prev = listOf(entry("Aniversário Bernardo", "f1", isDir = true)),
            view = viewOf(remote("f1", "Aniversário Bernardo", isDir = true, size = 4_096)),
        )

        assertEquals(listOf<Action>(Action.CreateFolderLocal("Aniversário Bernardo")), plan.actions)
    }

    @Test
    fun `a remote folder that changed size with the local folder present is not downloaded`() {
        val plan = plan(
            prev = listOf(entry("Aniversário Bernardo", "f1", isDir = true)),
            view = viewOf(remote("f1", "Aniversário Bernardo", isDir = true, size = 4_096)),
            local = listOf(local("Aniversário Bernardo", isDir = true)),
        )

        assertTrue(plan.actions.isEmpty())
        assertTrue(plan.pending.isEmpty())
        assertTrue(plan.conflicts.isEmpty())
    }

    @Test
    fun `a remote folder with a new id and no local copy is created instead of downloaded`() {
        val plan = plan(
            prev = listOf(entry("Fotos", "f1", isDir = true)),
            view = viewOf(remote("f2", "Fotos", isDir = true)),
        )

        assertEquals(listOf<Action>(Action.CreateFolderLocal("Fotos")), plan.actions)
        assertTrue(plan.actions.none { it is Action.Download })
    }

    @Test
    fun `a remote folder replacing a local file is a conflict and never a download`() {
        val plan = plan(
            prev = listOf(entry("Fotos", "r1", size = 5, mtimeMs = 100, localHash = HASH_A, remoteHash = HASH_A)),
            view = viewOf(remote("r1", "Fotos", isDir = true, size = 4_096)),
            local = listOf(local("Fotos", size = 5, mtimeMs = 100)),
            hashes = mapOf("Fotos" to HASH_A),
        )

        assertEquals("Fotos", plan.conflicts.single().relPath)
        assertTrue(plan.actions.none { it is Action.Download })
    }

    @Test
    fun `creates a remote folder for a new local folder`() {
        val plan = plan(local = listOf(local("Fotos", isDir = true)))

        assertEquals(listOf<Action>(Action.CreateFolderRemote("Fotos")), plan.actions)
    }

    @Test
    fun `queues a local delete for an empty local folder deleted remotely`() {
        val plan = plan(
            prev = listOf(entry("Fotos", "f1", isDir = true)),
            view = PairView(emptyMap(), setOf("f1")),
            local = listOf(local("Fotos", isDir = true)),
        )

        assertEquals(PendingKind.DELETE_LOCAL, plan.pending.single().kind)
        assertEquals("Fotos", plan.pending.single().relPath)
        assertTrue(plan.actions.isEmpty())
    }

    @Test
    fun `conflicts on a non empty local folder deleted remotely`() {
        val plan = plan(
            prev = listOf(
                entry("Fotos", "f1", isDir = true),
                entry("Fotos/a.txt", "a1", size = 5, mtimeMs = 100, localHash = HASH_A, remoteHash = HASH_A),
            ),
            view = PairView(emptyMap(), setOf("f1")),
            local = listOf(local("Fotos", isDir = true), local("Fotos/a.txt", size = 5, mtimeMs = 100)),
            hashes = mapOf("Fotos/a.txt" to HASH_A),
        )

        assertEquals("Fotos", plan.conflicts.single().relPath)
        assertTrue(plan.actions.isEmpty())
        assertTrue(plan.pending.isEmpty())
    }

    @Test
    fun `moves the local file on a remote rename without downloading`() {
        val plan = plan(
            prev = listOf(entry("a.txt", "r1", size = 5, mtimeMs = 100, localHash = HASH_A, remoteHash = HASH_A)),
            view = viewOf(remote("r1", "b.txt", size = 5, mtimeMs = 100, quickXorHash = HASH_A)),
            local = listOf(local("a.txt", size = 5, mtimeMs = 100)),
            hashes = mapOf("a.txt" to HASH_A),
        )

        assertEquals(listOf<Action>(Action.MoveLocal("a.txt", "b.txt")), plan.actions)
        assertTrue(plan.pending.isEmpty())
        assertTrue(plan.conflicts.isEmpty())
    }

    @Test
    fun `emits only the highest node for a remote folder move`() {
        val plan = plan(
            prev = listOf(
                entry("Antiga", "f1", isDir = true),
                entry("Antiga/x.txt", "x1", size = 5, mtimeMs = 100, localHash = HASH_A, remoteHash = HASH_A),
            ),
            view = PairView(
                mapOf(
                    "Nova" to remote("f1", "Nova", isDir = true),
                    "Nova/x.txt" to remote("x1", "x.txt", parentId = "f1", size = 5, mtimeMs = 100, quickXorHash = HASH_A),
                ),
                emptySet(),
            ),
            local = listOf(local("Antiga", isDir = true), local("Antiga/x.txt", size = 5, mtimeMs = 100)),
            hashes = mapOf("Antiga/x.txt" to HASH_A),
        )

        assertEquals(listOf<Action>(Action.MoveLocal("Antiga", "Nova")), plan.actions)
    }

    @Test
    fun `detects a local rename by size and hash`() {
        val plan = plan(
            prev = listOf(entry("velho.txt", "r1", size = 5, mtimeMs = 100, localHash = HASH_A, remoteHash = HASH_A)),
            view = viewOf(remote("r1", "velho.txt", size = 5, mtimeMs = 100, quickXorHash = HASH_A)),
            local = listOf(local("novo.txt", size = 5, mtimeMs = 200)),
            hashes = mapOf("novo.txt" to HASH_A),
        )

        assertEquals(listOf<Action>(Action.MoveRemote("velho.txt", "novo.txt", "r1")), plan.actions)
        assertTrue(plan.pending.isEmpty())
    }

    @Test
    fun `does not detect a local rename when the hash differs`() {
        val plan = plan(
            prev = listOf(entry("velho.txt", "r1", size = 5, mtimeMs = 100, localHash = HASH_A, remoteHash = HASH_A)),
            view = viewOf(remote("r1", "velho.txt", size = 5, mtimeMs = 100, quickXorHash = HASH_A)),
            local = listOf(local("novo.txt", size = 5, mtimeMs = 200)),
            hashes = mapOf("novo.txt" to HASH_B),
        )

        assertEquals(listOf<Action>(Action.Upload("novo.txt", "")), plan.actions)
        assertEquals(PendingKind.DELETE_REMOTE, plan.pending.single().kind)
        assertEquals("velho.txt", plan.pending.single().relPath)
        assertTrue(plan.actions.none { it is Action.MoveRemote })
    }

    @Test
    fun `orders actions folders first then moves then transfers`() {
        val plan = plan(
            prev = listOf(entry("velho.txt", "r9", size = 5, mtimeMs = 100, localHash = HASH_A, remoteHash = HASH_A)),
            view = PairView(
                mapOf(
                    "remota" to remote("d1", "remota", isDir = true),
                    "remota/x.txt" to remote("x1", "x.txt", parentId = "d1", size = 1, mtimeMs = 10, quickXorHash = HASH_B),
                    "renomeado.txt" to remote("r9", "renomeado.txt", size = 5, mtimeMs = 100, quickXorHash = HASH_A),
                ),
                emptySet(),
            ),
            local = listOf(
                local("velho.txt", size = 5, mtimeMs = 100),
                local("localDir", isDir = true),
                local("localDir/y.txt", size = 2, mtimeMs = 100),
            ),
            hashes = mapOf("velho.txt" to HASH_A, "localDir/y.txt" to HASH_B),
        )

        assertEquals(
            listOf(
                Action.CreateFolderRemote("localDir"),
                Action.CreateFolderLocal("remota"),
                Action.MoveLocal("velho.txt", "renomeado.txt"),
                Action.Download("remota/x.txt", "x1", 1L),
                Action.Upload("localDir/y.txt", null),
            ),
            plan.actions,
        )
    }

    private fun plan(
        prev: List<Entry> = emptyList(),
        view: PairView = PairView(emptyMap(), emptySet()),
        local: List<LocalFile> = emptyList(),
        hashes: Map<String, String> = emptyMap(),
    ): Plan = Planner.plan(PAIR, prev, view, local, hashes, NOW)

    private fun viewOf(vararg items: RemoteItem): PairView =
        PairView(items.associateBy { it.name.orEmpty() }, emptySet())

    private fun entry(
        relPath: String,
        remoteId: String?,
        isDir: Boolean = false,
        size: Long = 0,
        mtimeMs: Long = 0,
        localHash: String? = null,
        remoteHash: String? = null,
    ): Entry = Entry(
        id = 0,
        pairId = PAIR,
        relPath = relPath,
        parentRel = relPath.substringBeforeLast('/', "").ifEmpty { null },
        name = relPath.substringAfterLast('/'),
        isDir = isDir,
        size = size,
        mtimeMs = mtimeMs,
        localHash = localHash,
        remoteId = remoteId,
        remoteEtag = null,
        remoteHash = remoteHash,
        syncedAt = 0,
        state = EntryState.SYNCED,
    )

    private fun local(
        relPath: String,
        size: Long = 0,
        mtimeMs: Long = 0,
        isDir: Boolean = false,
    ): LocalFile = LocalFile(
        relPath = relPath,
        isDir = isDir,
        size = size,
        mtimeMs = mtimeMs,
        file = File(relPath),
    )

    private fun remote(
        id: String,
        name: String,
        parentId: String? = null,
        isDir: Boolean = false,
        size: Long = 0,
        mtimeMs: Long = 0,
        quickXorHash: String? = null,
    ): RemoteItem = RemoteItem(
        id = id,
        name = name,
        parentId = parentId,
        isDir = isDir,
        size = size,
        mtimeMs = mtimeMs,
        quickXorHash = quickXorHash,
        deleted = false,
    )

    private companion object {
        const val PAIR = 7L
        const val NOW = 1_700_000_000_000L
        val HASH_A = "a".repeat(40)
        val HASH_B = "b".repeat(40)
        val HASH_C = "c".repeat(40)
    }
}
