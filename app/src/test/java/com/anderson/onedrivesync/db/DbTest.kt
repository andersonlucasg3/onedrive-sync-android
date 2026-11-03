package com.anderson.onedrivesync.db

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Exercises the real SQLite layer (schema, DAOs and [DbIndexStore]) on the JVM through Robolectric.
 *
 * Every test starts from a fresh database file: [setUp] deletes whatever the previous test left
 * behind and opens a new [Db], so ordering between tests cannot influence the assertions.
 *
 * `sdk = [36]` matches the app's `targetSdk`, and a plain [Application] is used so the real
 * `App.onCreate` (WorkManager scheduling) does not write log rows into the database under test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class DbTest {
    private lateinit var context: Context
    private lateinit var db: Db
    private lateinit var sqlite: SQLiteDatabase
    private lateinit var store: DbIndexStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        deleteDatabases()
        db = Db(context)
        sqlite = db.writableDatabase
        store = DbIndexStore(db)
    }

    @After
    fun tearDown() {
        db.close()
        deleteDatabases()
    }

    // ---- schema -----------------------------------------------------------------------------

    @Test
    fun `onCreate creates all six tables and their indexes`() {
        assertEquals(EXPECTED_TABLES, tableNames(sqlite))
        assertEquals(EXPECTED_INDEXES, indexNames(sqlite))
    }

    // A second SQLiteOpenHelper on the same file sees version 1 and skips onCreate; the DDL must
    // therefore never run twice for a database the app already created.
    @Test
    fun `reopening the database keeps the schema and the rows`() {
        val pairId = insertPair(displayName = "Docs")
        db.close()

        val second = Db(context)
        val secondSqlite = second.writableDatabase
        try {
            assertEquals(EXPECTED_TABLES, tableNames(secondSqlite))
            assertEquals(listOf(pairId), PairsDao.list(secondSqlite).map { it.id })
        } finally {
            second.close()
        }
    }

    // ---- PairsDao ---------------------------------------------------------------------------

    @Test
    fun `list returns pairs ordered by creation and inserts defaults`() {
        val first = insertPair(displayName = "First")
        val second = insertPair(displayName = "Second")
        val third = insertPair(displayName = "Third")

        assertEquals(listOf(first, second, third), PairsDao.list(sqlite).map { it.id })

        val stored = checkNotNull(PairsDao.get(sqlite, first)) { "pair $first disappeared" }
        assertEquals("remote-id", stored.remoteId)
        assertEquals("/remote/root", stored.remotePath)
        assertEquals("/local/root", stored.localPath)
        assertEquals("First", stored.displayName)
        assertTrue(stored.enabled)
        assertTrue(stored.createdAt > 0L)
        assertNull(stored.lastSyncAt)
    }

    @Test
    fun `get returns null for an unknown id`() {
        assertNull(PairsDao.get(sqlite, 999L))
    }

    @Test
    fun `updateLastSync stores the timestamp without touching other pairs`() {
        val pairId = insertPair()
        val untouched = insertPair()

        PairsDao.updateLastSync(sqlite, pairId, 7_000L)

        assertEquals(7_000L, PairsDao.get(sqlite, pairId)?.lastSyncAt)
        assertNull(PairsDao.get(sqlite, untouched)?.lastSyncAt)
    }

    @Test
    fun `setEnabled toggles the flag both ways`() {
        val pairId = insertPair()
        assertTrue(checkNotNull(PairsDao.get(sqlite, pairId)).enabled)

        PairsDao.setEnabled(sqlite, pairId, false)
        assertFalse(checkNotNull(PairsDao.get(sqlite, pairId)).enabled)

        PairsDao.setEnabled(sqlite, pairId, true)
        assertTrue(checkNotNull(PairsDao.get(sqlite, pairId)).enabled)
    }

    @Test
    fun `delete removes the pair and cascades to its entries pending ops and conflicts only`() {
        val doomed = insertPair(displayName = "Doomed")
        val kept = insertPair(displayName = "Kept")
        EntriesDao.upsert(sqlite, entryRow(doomed, relPath = "doomed.txt"))
        EntriesDao.upsert(sqlite, entryRow(kept, relPath = "kept.txt"))
        PendingDao.insert(sqlite, pendingOp(doomed, relPath = "doomed.txt"))
        PendingDao.insert(sqlite, pendingOp(kept, relPath = "kept.txt"))
        ConflictsDao.insert(sqlite, conflictRow(doomed, relPath = "doomed.txt"))
        ConflictsDao.insert(sqlite, conflictRow(kept, relPath = "kept.txt"))

        PairsDao.delete(sqlite, doomed)

        assertNull(PairsDao.get(sqlite, doomed))
        assertEquals(0, EntriesDao.countByPair(sqlite, doomed))
        assertEquals(0, PendingDao.listByPair(sqlite, doomed).size)
        assertTrue(ConflictsDao.list(sqlite).none { it.pairId == doomed })

        assertEquals(1, EntriesDao.countByPair(sqlite, kept))
        assertEquals(1, PendingDao.listByPair(sqlite, kept).size)
        assertEquals(1, ConflictsDao.list(sqlite).count { it.pairId == kept })
        assertEquals(listOf(kept), PairsDao.list(sqlite).map { it.id })
    }

    // ---- EntriesDao -------------------------------------------------------------------------

    @Test
    fun `upsert inserts a new entry with every column round tripped`() {
        val pairId = insertPair()
        EntriesDao.upsert(
            sqlite,
            entryRow(
                pairId = pairId,
                relPath = "Docs/a.txt",
                parentRel = "Docs",
                size = 42L,
                mtimeMs = 1_600_000_000_123L,
                localHash = "local-1",
                remoteId = "remote-1",
                remoteEtag = "etag-1",
                remoteHash = "remote-hash-1",
                syncedAt = 1_600_000_001_000L,
            ),
        )

        val stored = checkNotNull(EntriesDao.getByRelPath(sqlite, pairId, "Docs/a.txt"))
        assertTrue(stored.id > 0L)
        assertEquals(pairId, stored.pairId)
        assertEquals("Docs/a.txt", stored.relPath)
        assertEquals("Docs", stored.parentRel)
        assertEquals("a.txt", stored.name)
        assertFalse(stored.isDir)
        assertEquals(42L, stored.size)
        assertEquals(1_600_000_000_123L, stored.mtimeMs)
        assertEquals("local-1", stored.localHash)
        assertEquals("remote-1", stored.remoteId)
        assertEquals("etag-1", stored.remoteEtag)
        assertEquals("remote-hash-1", stored.remoteHash)
        assertEquals(1_600_000_001_000L, stored.syncedAt)
        assertEquals(EntryState.SYNCED, stored.state)
    }

    @Test
    fun `upsert updates the existing row and preserves its id`() {
        val pairId = insertPair()
        EntriesDao.upsert(sqlite, entryRow(pairId, relPath = "a.txt", size = 1L))
        val original = checkNotNull(EntriesDao.getByRelPath(sqlite, pairId, "a.txt"))

        EntriesDao.upsert(
            sqlite,
            original.copy(size = 99L, state = EntryState.DIRTY, localHash = "local-2", syncedAt = 9_000L),
        )

        val updated = checkNotNull(EntriesDao.getByRelPath(sqlite, pairId, "a.txt"))
        assertEquals(original.id, updated.id)
        assertEquals(1, EntriesDao.countByPair(sqlite, pairId))
        assertEquals(99L, updated.size)
        assertEquals(EntryState.DIRTY, updated.state)
        assertEquals("local-2", updated.localHash)
        assertEquals(9_000L, updated.syncedAt)
    }

    @Test
    fun `the same rel path coexists in two different pairs`() {
        val first = insertPair()
        val second = insertPair()

        EntriesDao.upsert(sqlite, entryRow(first, relPath = "same.txt", size = 1L))
        EntriesDao.upsert(sqlite, entryRow(second, relPath = "same.txt", size = 2L))

        assertEquals(1, EntriesDao.countByPair(sqlite, first))
        assertEquals(1, EntriesDao.countByPair(sqlite, second))
        assertEquals(1L, checkNotNull(EntriesDao.getByRelPath(sqlite, first, "same.txt")).size)
        assertEquals(2L, checkNotNull(EntriesDao.getByRelPath(sqlite, second, "same.txt")).size)
    }

    @Test
    fun `getByRemoteId is scoped to the pair`() {
        val first = insertPair()
        val second = insertPair()
        EntriesDao.upsert(sqlite, entryRow(first, relPath = "a.txt", remoteId = "remote-a"))
        EntriesDao.upsert(sqlite, entryRow(second, relPath = "b.txt", remoteId = "remote-b"))

        assertEquals("a.txt", EntriesDao.getByRemoteId(sqlite, first, "remote-a")?.relPath)
        assertNull(EntriesDao.getByRemoteId(sqlite, second, "remote-a"))
        assertNull(EntriesDao.getByRemoteId(sqlite, first, "remote-b"))
        assertNull(EntriesDao.getByRemoteId(sqlite, first, "unknown"))
    }

    @Test
    fun `deleteByRelPath removes only that path in that pair`() {
        val first = insertPair()
        val second = insertPair()
        EntriesDao.upsert(sqlite, entryRow(first, relPath = "a.txt"))
        EntriesDao.upsert(sqlite, entryRow(first, relPath = "b.txt"))
        EntriesDao.upsert(sqlite, entryRow(second, relPath = "a.txt"))

        EntriesDao.deleteByRelPath(sqlite, first, "a.txt")

        assertNull(EntriesDao.getByRelPath(sqlite, first, "a.txt"))
        assertEquals(1, EntriesDao.countByPair(sqlite, first))
        assertEquals(1, EntriesDao.countByPair(sqlite, second))
    }

    @Test
    fun `deleteByPair removes every entry of that pair and nothing else`() {
        val first = insertPair()
        val second = insertPair()
        EntriesDao.upsert(sqlite, entryRow(first, relPath = "a.txt"))
        EntriesDao.upsert(sqlite, entryRow(first, relPath = "b.txt"))
        EntriesDao.upsert(sqlite, entryRow(second, relPath = "c.txt"))

        EntriesDao.deleteByPair(sqlite, first)

        assertEquals(0, EntriesDao.countByPair(sqlite, first))
        assertTrue(EntriesDao.listByPair(sqlite, first).isEmpty())
        assertEquals(1, EntriesDao.countByPair(sqlite, second))
    }

    @Test
    fun `countByPair counts only the requested pair`() {
        val first = insertPair()
        val second = insertPair()
        EntriesDao.upsert(sqlite, entryRow(first, relPath = "a.txt"))
        EntriesDao.upsert(sqlite, entryRow(first, relPath = "b.txt"))
        EntriesDao.upsert(sqlite, entryRow(second, relPath = "c.txt"))

        assertEquals(2, EntriesDao.countByPair(sqlite, first))
        assertEquals(1, EntriesDao.countByPair(sqlite, second))
        assertEquals(0, EntriesDao.countByPair(sqlite, 404L))
    }

    @Test
    fun `entry round trip keeps accents spaces and cedilla after reopening the database`() {
        val pairId = insertPair()
        val relPath = "Documentos/Pasta ção/currículo final (v2).txt"
        val parentRel = "Documentos/Pasta ção"
        val name = "currículo final (v2).txt"

        EntriesDao.upsert(
            sqlite,
            entryRow(pairId, relPath = relPath, parentRel = parentRel, name = name, remoteId = "remote-ação"),
        )
        db.close()

        val second = Db(context)
        val secondSqlite = second.writableDatabase
        try {
            val stored = checkNotNull(EntriesDao.getByRelPath(secondSqlite, pairId, relPath))
            assertEquals(relPath, stored.relPath)
            assertEquals(parentRel, stored.parentRel)
            assertEquals(name, stored.name)
            assertEquals("remote-ação", stored.remoteId)
            assertEquals(relPath, EntriesDao.listByPair(secondSqlite, pairId).single().relPath)
        } finally {
            second.close()
        }
    }

    // ---- PendingDao -------------------------------------------------------------------------

    @Test
    fun `list returns pending ops ordered by creation time`() {
        val pairId = insertPair()
        val oldest = PendingDao.insert(sqlite, pendingOp(pairId, relPath = "b.txt", createdAt = 10L))
        val middle = PendingDao.insert(sqlite, pendingOp(pairId, relPath = "c.txt", createdAt = 20L))
        val newest = PendingDao.insert(sqlite, pendingOp(pairId, relPath = "a.txt", createdAt = 30L))

        assertEquals(listOf(oldest, middle, newest), PendingDao.list(sqlite).map { it.id })
    }

    @Test
    fun `listByPair filters by pair and insert round trips the columns`() {
        val first = insertPair()
        val second = insertPair()
        PendingDao.insert(
            sqlite,
            pendingOp(
                first,
                kind = PendingKind.DOWNLOAD,
                relPath = "Docs/a.txt",
                remoteId = "remote-1",
                detail = "remote is newer",
                createdAt = 5_000L,
            ),
        )
        PendingDao.insert(sqlite, pendingOp(second, kind = PendingKind.UPLOAD, relPath = "b.txt"))

        val stored = PendingDao.listByPair(sqlite, first).single()
        assertEquals(PendingKind.DOWNLOAD, stored.kind)
        assertEquals("Docs/a.txt", stored.relPath)
        assertEquals("remote-1", stored.remoteId)
        assertEquals("remote is newer", stored.detail)
        assertEquals(5_000L, stored.createdAt)
        assertEquals(1, PendingDao.listByPair(sqlite, second).size)
    }

    @Test
    fun `get returns the queued op or null`() {
        val pairId = insertPair()
        val id = PendingDao.insert(sqlite, pendingOp(pairId, kind = PendingKind.MOVE_LOCAL, relPath = "old.txt"))

        val stored = checkNotNull(PendingDao.get(sqlite, id))
        assertEquals(PendingKind.MOVE_LOCAL, stored.kind)
        assertEquals("old.txt", stored.relPath)
        assertNull(stored.detail)
        assertNull(PendingDao.get(sqlite, id + 1))
    }

    @Test
    fun `delete removes only the requested op`() {
        val pairId = insertPair()
        val first = PendingDao.insert(sqlite, pendingOp(pairId, relPath = "a.txt"))
        val second = PendingDao.insert(sqlite, pendingOp(pairId, relPath = "b.txt"))

        PendingDao.delete(sqlite, first)

        assertNull(PendingDao.get(sqlite, first))
        assertEquals(listOf(second), PendingDao.list(sqlite).map { it.id })
    }

    @Test
    fun `exists matches pair kind and rel path and rejects every difference`() {
        val pairId = insertPair()
        val otherPair = insertPair()
        PendingDao.insert(sqlite, pendingOp(pairId, kind = PendingKind.DOWNLOAD, relPath = "Docs/a.txt"))

        assertTrue(PendingDao.exists(sqlite, pairId, PendingKind.DOWNLOAD, "Docs/a.txt"))
        assertFalse(PendingDao.exists(sqlite, pairId, PendingKind.UPLOAD, "Docs/a.txt"))
        assertFalse(PendingDao.exists(sqlite, pairId, PendingKind.DOWNLOAD, "Docs/b.txt"))
        assertFalse(PendingDao.exists(sqlite, otherPair, PendingKind.DOWNLOAD, "Docs/a.txt"))
        assertFalse(PendingDao.exists(sqlite, pairId, PendingKind.DOWNLOAD, "docs/a.txt"))
    }

    // ---- ConflictsDao -----------------------------------------------------------------------

    @Test
    fun `insert and list round trip conflicts ordered by creation`() {
        val pairId = insertPair()
        val first = ConflictsDao.insert(sqlite, conflictRow(pairId, relPath = "a.txt", createdAt = 1_000L))
        val second = ConflictsDao.insert(sqlite, conflictRow(pairId, relPath = "b.txt", createdAt = 2_000L))

        val rows = ConflictsDao.list(sqlite)
        assertEquals(listOf(first, second), rows.map { it.id })
        assertEquals("a.txt", rows[0].relPath)
        assertEquals(100L, rows[0].localMtimeMs)
        assertEquals(200L, rows[0].remoteMtimeMs)
        assertEquals(10L, rows[0].localSize)
        assertEquals(20L, rows[0].remoteSize)
        assertNull(rows[0].decision)
    }

    @Test
    fun `get returns the conflict or null`() {
        val pairId = insertPair()
        val id = ConflictsDao.insert(sqlite, conflictRow(pairId, relPath = "a.txt", remoteId = "remote-1"))

        assertEquals("a.txt", checkNotNull(ConflictsDao.get(sqlite, id)).relPath)
        assertEquals("remote-1", checkNotNull(ConflictsDao.get(sqlite, id)).remoteId)
        assertNull(ConflictsDao.get(sqlite, id + 1))
    }

    @Test
    fun `setDecision updates and can clear the decision`() {
        val pairId = insertPair()
        val id = ConflictsDao.insert(sqlite, conflictRow(pairId, relPath = "a.txt"))
        assertNull(checkNotNull(ConflictsDao.get(sqlite, id)).decision)

        ConflictsDao.setDecision(sqlite, id, "LOCAL")
        assertEquals("LOCAL", checkNotNull(ConflictsDao.get(sqlite, id)).decision)

        ConflictsDao.setDecision(sqlite, id, null)
        assertNull(checkNotNull(ConflictsDao.get(sqlite, id)).decision)
    }

    @Test
    fun `delete removes only the requested conflict`() {
        val pairId = insertPair()
        val first = ConflictsDao.insert(sqlite, conflictRow(pairId, relPath = "a.txt"))
        val second = ConflictsDao.insert(sqlite, conflictRow(pairId, relPath = "b.txt"))

        ConflictsDao.delete(sqlite, first)

        assertNull(ConflictsDao.get(sqlite, first))
        assertEquals(listOf(second), ConflictsDao.list(sqlite).map { it.id })
    }

    // ---- LogDao -----------------------------------------------------------------------------

    @Test
    fun `recent returns newest first and honours the limit`() {
        LogDao.insert(sqlite, 100L, "I", "SyncEngine", "first")
        LogDao.insert(sqlite, 200L, "W", "Graph", "second")
        LogDao.insert(sqlite, 300L, "E", "Executor", "third")

        val rows = LogDao.recent(sqlite)
        assertEquals(listOf("third", "second", "first"), rows.map { it.msg })
        assertEquals(300L, rows[0].ts)
        assertEquals("E", rows[0].level)
        assertEquals("Executor", rows[0].tag)

        assertEquals(listOf("third", "second"), LogDao.recent(sqlite, limit = 2).map { it.msg })
        assertEquals(listOf("third"), LogDao.recent(sqlite, limit = 1).map { it.msg })
    }

    @Test
    fun `recent breaks timestamp ties by newest insertion`() {
        LogDao.insert(sqlite, 500L, "I", "TAG", "older")
        LogDao.insert(sqlite, 500L, "I", "TAG", "newer")

        assertEquals(listOf("newer", "older"), LogDao.recent(sqlite).map { it.msg })
    }

    @Test
    fun `prune keeps only the newest rows`() {
        for (index in 1..5) {
            LogDao.insert(sqlite, index.toLong(), "I", "TAG", "msg-$index")
        }

        LogDao.prune(sqlite, keep = 2)

        assertEquals(2, LogDao.count(sqlite))
        assertEquals(listOf("msg-5", "msg-4"), LogDao.recent(sqlite).map { it.msg })
    }

    @Test
    fun `prune keeps every row when the limit exceeds the row count`() {
        LogDao.insert(sqlite, 1L, "I", "TAG", "only")
        LogDao.insert(sqlite, 2L, "I", "TAG", "second")

        LogDao.prune(sqlite, keep = 10)

        assertEquals(2, LogDao.count(sqlite))
    }

    @Test
    fun `count tracks inserts and clear empties the table`() {
        assertTrue(LogDao.recent(sqlite).isEmpty())
        LogDao.insert(sqlite, 1L, "I", "TAG", "a")
        LogDao.insert(sqlite, 2L, "I", "TAG", "b")
        LogDao.insert(sqlite, 3L, "I", "TAG", "c")
        assertEquals(3, LogDao.count(sqlite))

        LogDao.clear(sqlite)

        assertEquals(0, LogDao.count(sqlite))
        assertTrue(LogDao.recent(sqlite).isEmpty())
    }

    // ---- StateDao ---------------------------------------------------------------------------

    @Test
    fun `state stores overwrites and reads values`() {
        assertNull(StateDao.get(sqlite, "delta_link"))

        StateDao.set(sqlite, "delta_link", "token-1")
        assertEquals("token-1", StateDao.get(sqlite, "delta_link"))

        StateDao.set(sqlite, "delta_link", "token-2")
        assertEquals("token-2", StateDao.get(sqlite, "delta_link"))
        assertEquals(1, rawCount("state"))
    }

    @Test
    fun `state remove deletes the key`() {
        StateDao.set(sqlite, "selected_pair", "7")

        StateDao.remove(sqlite, "selected_pair")

        assertNull(StateDao.get(sqlite, "selected_pair"))
        assertEquals(0, rawCount("state"))
    }

    // ---- DbIndexStore -----------------------------------------------------------------------

    @Test
    fun `store listPairs filters disabled pairs when asked`() {
        val enabled = insertPair(displayName = "Enabled")
        val disabled = insertPair(displayName = "Disabled")
        PairsDao.setEnabled(sqlite, disabled, false)

        assertEquals(listOf(enabled, disabled), store.listPairs(enabledOnly = false).map { it.id })
        assertEquals(listOf(enabled), store.listPairs(enabledOnly = true).map { it.id })
        assertNull(store.getPair(404L))
    }

    @Test
    fun `store touchPair persists last sync at`() {
        val pairId = insertPair()

        store.touchPair(pairId, 4_242L)

        assertEquals(4_242L, checkNotNull(store.getPair(pairId)).lastSyncAt)
    }

    @Test
    fun `store log writes a row visible through LogDao`() {
        store.log(9_000L, "W", "SyncEngine", "falhou ao enviar")

        val row = LogDao.recent(sqlite).single()
        assertEquals(9_000L, row.ts)
        assertEquals("W", row.level)
        assertEquals("SyncEngine", row.tag)
        assertEquals("falhou ao enviar", row.msg)
    }

    @Test
    fun `store getState and setState delegate and a null value deletes the row`() {
        assertNull(store.getState("delta_link"))

        store.setState("delta_link", "abc")
        assertEquals("abc", store.getState("delta_link"))
        assertEquals("abc", StateDao.get(sqlite, "delta_link"))

        store.setState("delta_link", null)
        assertNull(store.getState("delta_link"))
        assertNull(StateDao.get(sqlite, "delta_link"))
        assertEquals(0, rawCount("state"))
    }

    @Test
    fun `store entries upsert and delete delegate to EntriesDao`() {
        val pairId = insertPair()

        store.upsertEntry(entryRow(pairId, relPath = "a.txt", size = 7L))
        assertEquals(listOf("a.txt"), store.entries(pairId).map { it.relPath })

        store.upsertEntry(entryRow(pairId, relPath = "a.txt", size = 8L))
        assertEquals(8L, store.entries(pairId).single().size)

        store.deleteEntry(pairId, "a.txt")
        assertTrue(store.entries(pairId).isEmpty())
    }

    @Test
    fun `store pending operations delegate to PendingDao`() {
        val pairId = insertPair()

        store.addPending(pendingOp(pairId, kind = PendingKind.DOWNLOAD, relPath = "a.txt"))
        val queued = store.listPending().single()
        assertEquals(PendingKind.DOWNLOAD, queued.kind)
        assertEquals(queued.id, checkNotNull(store.getPending(queued.id)).id)

        store.deletePending(queued.id)

        assertNull(store.getPending(queued.id))
        assertTrue(store.listPending().isEmpty())
    }

    @Test
    fun `store conflicts delegate to ConflictsDao`() {
        val pairId = insertPair()

        store.addConflict(conflictRow(pairId, relPath = "a.txt"))
        val queued = store.listConflicts().single()
        assertEquals("a.txt", queued.relPath)
        assertEquals(queued.id, checkNotNull(store.getConflict(queued.id)).id)

        store.deleteConflict(queued.id)

        assertNull(store.getConflict(queued.id))
        assertTrue(store.listConflicts().isEmpty())
    }

    // ---- helpers ----------------------------------------------------------------------------

    private fun deleteDatabases() {
        context.databaseList().forEach { context.deleteDatabase(it) }
    }

    private fun insertPair(
        remoteId: String = "remote-id",
        remotePath: String = "/remote/root",
        localPath: String = "/local/root",
        displayName: String = "OneDrive",
    ): Long = PairsDao.insert(sqlite, remoteId, remotePath, localPath, displayName)

    private fun tableNames(database: SQLiteDatabase): Set<String> =
        database.rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
            null,
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0)
                    if (name != "android_metadata") add(name)
                }
            }
        }

    private fun indexNames(database: SQLiteDatabase): Set<String> =
        database.rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND name NOT LIKE 'sqlite_%'",
            null,
        ).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    private fun rawCount(table: String): Int =
        sqlite.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    private companion object {
        val EXPECTED_TABLES = setOf("pairs", "state", "entries", "pending", "conflicts", "log")
        val EXPECTED_INDEXES = setOf(
            "idx_entries_pair_id",
            "idx_entries_remote_id",
            "idx_pending_pair_id",
            "idx_log_ts",
        )
    }
}

private fun entryRow(
    pairId: Long,
    relPath: String,
    parentRel: String? = null,
    name: String = relPath.substringAfterLast('/'),
    isDir: Boolean = false,
    size: Long = 10L,
    mtimeMs: Long = 1_600_000_000_000L,
    localHash: String? = "local-hash",
    remoteId: String? = null,
    remoteEtag: String? = null,
    remoteHash: String? = null,
    syncedAt: Long = 1_600_000_001_000L,
    state: EntryState = EntryState.SYNCED,
): Entry = Entry(
    id = 0L,
    pairId = pairId,
    relPath = relPath,
    parentRel = parentRel,
    name = name,
    isDir = isDir,
    size = size,
    mtimeMs = mtimeMs,
    localHash = localHash,
    remoteId = remoteId,
    remoteEtag = remoteEtag,
    remoteHash = remoteHash,
    syncedAt = syncedAt,
    state = state,
)

private fun pendingOp(
    pairId: Long,
    kind: PendingKind = PendingKind.UPLOAD,
    relPath: String = "Docs/a.txt",
    remoteId: String? = null,
    detail: String? = null,
    createdAt: Long = 1_000L,
): PendingOp = PendingOp(
    id = 0L,
    pairId = pairId,
    kind = kind,
    relPath = relPath,
    remoteId = remoteId,
    detail = detail,
    createdAt = createdAt,
)

private fun conflictRow(
    pairId: Long,
    relPath: String,
    remoteId: String? = null,
    localMtimeMs: Long = 100L,
    remoteMtimeMs: Long = 200L,
    localSize: Long = 10L,
    remoteSize: Long = 20L,
    decision: String? = null,
    createdAt: Long = 1_000L,
): ConflictRow = ConflictRow(
    id = 0L,
    pairId = pairId,
    relPath = relPath,
    remoteId = remoteId,
    localMtimeMs = localMtimeMs,
    remoteMtimeMs = remoteMtimeMs,
    localSize = localSize,
    remoteSize = remoteSize,
    decision = decision,
    createdAt = createdAt,
)
