package com.anderson.onedrivesync.sync

import com.anderson.onedrivesync.db.ConflictRow
import com.anderson.onedrivesync.db.Entry
import com.anderson.onedrivesync.db.Pair
import com.anderson.onedrivesync.db.PendingOp

/**
 * Everything the sync cycle needs from the database, expressed only with domain types so the
 * engine and executor run against an in-memory implementation in tests.
 *
 * Android implementation: `db.DbIndexStore` over the existing DAOs.
 */
interface IndexStore {
    fun listPairs(enabledOnly: Boolean): List<Pair>
    fun getPair(id: Long): Pair?
    fun entries(pairId: Long): List<Entry>
    fun upsertEntry(entry: Entry)
    fun deleteEntry(pairId: Long, relPath: String)
    fun addPending(op: PendingOp)
    fun addConflict(row: ConflictRow)
    fun listPending(): List<PendingOp>
    fun getPending(id: Long): PendingOp?
    fun deletePending(id: Long)
    fun listConflicts(): List<ConflictRow>
    fun getConflict(id: Long): ConflictRow?
    fun deleteConflict(id: Long)
    fun getState(key: String): String?
    fun setState(key: String, value: String?)
    fun touchPair(pairId: Long, ts: Long)
    fun log(ts: Long, level: String, tag: String, msg: String)
}
