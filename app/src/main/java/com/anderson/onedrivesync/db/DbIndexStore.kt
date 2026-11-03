package com.anderson.onedrivesync.db

import com.anderson.onedrivesync.sync.IndexStore

/** [IndexStore] over the SQLite schema created by [Db]; every call runs on the caller's thread. */
class DbIndexStore(private val db: Db) : IndexStore {
    private val database get() = db.writableDatabase

    override fun listPairs(enabledOnly: Boolean): List<Pair> =
        PairsDao.list(database).filter { !enabledOnly || it.enabled }

    override fun getPair(id: Long): Pair? = PairsDao.get(database, id)

    override fun entries(pairId: Long): List<Entry> = EntriesDao.listByPair(database, pairId)

    override fun upsertEntry(entry: Entry) = EntriesDao.upsert(database, entry)

    override fun deleteEntry(pairId: Long, relPath: String) = EntriesDao.deleteByRelPath(database, pairId, relPath)

    override fun addPending(op: PendingOp) {
        PendingDao.insert(database, op)
    }

    override fun addConflict(row: ConflictRow) {
        ConflictsDao.insert(database, row)
    }

    override fun listPending(): List<PendingOp> = PendingDao.list(database)

    override fun getPending(id: Long): PendingOp? = PendingDao.get(database, id)

    override fun deletePending(id: Long) = PendingDao.delete(database, id)

    override fun listConflicts(): List<ConflictRow> = ConflictsDao.list(database)

    override fun getConflict(id: Long): ConflictRow? = ConflictsDao.get(database, id)

    override fun deleteConflict(id: Long) = ConflictsDao.delete(database, id)

    override fun getState(key: String): String? = StateDao.get(database, key)

    override fun setState(key: String, value: String?) {
        if (value == null) StateDao.remove(database, key) else StateDao.set(database, key, value)
    }

    override fun touchPair(pairId: Long, ts: Long) = PairsDao.updateLastSync(database, pairId, ts)

    override fun log(ts: Long, level: String, tag: String, msg: String) = LogDao.insert(database, ts, level, tag, msg)
}
