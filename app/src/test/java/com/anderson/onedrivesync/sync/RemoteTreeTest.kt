package com.anderson.onedrivesync.sync

import com.anderson.onedrivesync.db.Entry
import com.anderson.onedrivesync.db.EntryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteTreeTest {
    @Test
    fun `resolves a simple tree from out of order items`() {
        val items = listOf(
            remote(id = "f2", name = "b.txt", parentId = "root"),
            remote(id = "f3", name = "c.txt", parentId = "docs"),
            remote(id = "docs", name = "docs", parentId = "root", isDir = true),
        )

        val view = RemoteTree.resolve("root", emptyList(), items)

        assertEquals(setOf("docs", "docs/c.txt", "b.txt"), view.items.keys)
        assertEquals("f3", view.items["docs/c.txt"]?.id)
        assertTrue(view.deletedIds.isEmpty())
        assertTrue(view.collisions.isEmpty())
    }

    @Test
    fun `keeps the last occurrence of a duplicated id`() {
        val items = listOf(
            remote(id = "x", name = "old.txt", parentId = "root", size = 1),
            remote(id = "x", name = "new.txt", parentId = "root", size = 2),
        )

        val view = RemoteTree.resolve("root", emptyList(), items)

        assertEquals(setOf("new.txt"), view.items.keys)
        assertEquals(2L, view.items["new.txt"]?.size)
    }

    @Test
    fun `propagates a deleted folder to its known descendants`() {
        val prev = listOf(
            entry("Docs", "d1", isDir = true),
            entry("Docs/a.txt", "a1"),
            entry("Docs/sub", "s1", isDir = true),
            entry("Docs/sub/b.txt", "b1"),
        )
        val items = listOf(remote(id = "d1", name = null, parentId = "root", deleted = true))

        val view = RemoteTree.resolve("root", prev, items)

        assertTrue(view.items.isEmpty())
        assertEquals(setOf("d1", "a1", "s1", "b1"), view.deletedIds)
    }

    @Test
    fun `leaves items with an unknown ancestor out of the map`() {
        val items = listOf(
            remote(id = "c1", name = "a.txt", parentId = "ghost"),
            remote(id = "g1", name = "deep.txt", parentId = "c1"),
        )

        val view = RemoteTree.resolve("root", emptyList(), items)

        assertTrue(view.items.isEmpty())
        assertTrue(view.deletedIds.isEmpty())
    }

    @Test
    fun `resolves through an unchanged ancestor known from the previous index`() {
        val prev = listOf(entry("Docs", "d1", isDir = true))
        val items = listOf(remote(id = "n1", name = "novo.txt", parentId = "d1"))

        val view = RemoteTree.resolve("root", prev, items)

        assertEquals(setOf("Docs/novo.txt"), view.items.keys)
    }

    @Test
    fun `does not silently overwrite a path collision`() {
        val items = listOf(
            remote(id = "i1", name = "a.txt", parentId = "root", size = 1, mtimeMs = 100),
            remote(id = "i2", name = "a.txt", parentId = "root", size = 2, mtimeMs = 200),
            remote(id = "i3", name = "A.TXT", parentId = "root", size = 3, mtimeMs = 50),
        )

        val view = RemoteTree.resolve("root", emptyList(), items)

        assertEquals(1, view.items.size)
        assertEquals("i2", view.items["a.txt"]?.id)
        assertEquals(setOf("i1", "i3"), view.collisions.map { it.id }.toSet())
    }

    @Test
    fun `handles accents, spaces and a deeply nested path`() {
        val items = listOf(
            remote(id = "n3", name = "Relatório final.pdf", parentId = "n2"),
            remote(id = "n1", name = "Pasta Céu", parentId = "root", isDir = true),
            remote(id = "n2", name = "Ação & Cia", parentId = "n1", isDir = true),
        )

        val view = RemoteTree.resolve("root", emptyList(), items)

        assertEquals(
            setOf("Pasta Céu", "Pasta Céu/Ação & Cia", "Pasta Céu/Ação & Cia/Relatório final.pdf"),
            view.items.keys,
        )
    }

    private fun entry(relPath: String, remoteId: String, isDir: Boolean = false): Entry = Entry(
        id = 0,
        pairId = 1,
        relPath = relPath,
        parentRel = relPath.substringBeforeLast('/', "").ifEmpty { null },
        name = relPath.substringAfterLast('/'),
        isDir = isDir,
        size = 0,
        mtimeMs = 0,
        localHash = null,
        remoteId = remoteId,
        remoteEtag = null,
        remoteHash = null,
        syncedAt = 0,
        state = EntryState.SYNCED,
    )

    private fun remote(
        id: String,
        name: String?,
        parentId: String?,
        isDir: Boolean = false,
        size: Long = 0,
        mtimeMs: Long = 0,
        quickXorHash: String? = null,
        deleted: Boolean = false,
    ): RemoteItem = RemoteItem(
        id = id,
        name = name,
        parentId = parentId,
        isDir = isDir,
        size = size,
        mtimeMs = mtimeMs,
        quickXorHash = quickXorHash,
        deleted = deleted,
    )
}
