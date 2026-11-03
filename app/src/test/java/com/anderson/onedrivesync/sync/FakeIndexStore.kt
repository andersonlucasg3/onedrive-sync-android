package com.anderson.onedrivesync.sync

import com.anderson.onedrivesync.db.ConflictRow
import com.anderson.onedrivesync.db.Entry
import com.anderson.onedrivesync.db.Pair
import com.anderson.onedrivesync.db.PendingOp

/** In-memory [IndexStore]; upserts follow the DAO contract (key = pair + rel path). */
class FakeIndexStore(pairs: List<Pair> = emptyList()) : IndexStore {
    private val pairsById = pairs.associateBy { it.id }.toMutableMap()
    private val entries = LinkedHashMap<String, Entry>()
    private val pending = LinkedHashMap<Long, PendingOp>()
    private val conflicts = LinkedHashMap<Long, ConflictRow>()
    private val state = HashMap<String, String>()
    private val logLines = ArrayList<String>()
    private var nextId = 1L

    val pendingOps: List<PendingOp> get() = pending.values.toList()
    val conflictRows: List<ConflictRow> get() = conflicts.values.toList()
    val logs: List<String> get() = logLines

    fun entriesOf(pairId: Long): List<Entry> = entries.values.filter { it.pairId == pairId }.sortedBy { it.relPath }

    /** Simulates the pair wizard inserting a pair after earlier syncs already advanced the cursor. */
    fun addPair(pair: Pair) {
        pairsById[pair.id] = pair
    }

    fun stateOf(key: String): String? = state[key]

    override fun listPairs(enabledOnly: Boolean): List<Pair> = pairsById.values.filter { !enabledOnly || it.enabled }

    override fun getPair(id: Long): Pair? = pairsById[id]

    override fun entries(pairId: Long): List<Entry> = entriesOf(pairId)

    override fun upsertEntry(entry: Entry) {
        val key = key(entry.pairId, entry.relPath)
        entries[key] = entry.copy(id = entries[key]?.id ?: nextId++)
    }

    override fun deleteEntry(pairId: Long, relPath: String) {
        entries.remove(key(pairId, relPath))
    }

    override fun addPending(op: PendingOp) {
        val id = op.id.takeIf { it != 0L } ?: nextId++
        pending[id] = op.copy(id = id)
    }

    override fun addConflict(row: ConflictRow) {
        val id = row.id.takeIf { it != 0L } ?: nextId++
        conflicts[id] = row.copy(id = id)
    }

    override fun listPending(): List<PendingOp> = pending.values.toList()

    override fun getPending(id: Long): PendingOp? = pending[id]

    override fun deletePending(id: Long) {
        pending.remove(id)
    }

    override fun listConflicts(): List<ConflictRow> = conflicts.values.toList()

    override fun getConflict(id: Long): ConflictRow? = conflicts[id]

    override fun deleteConflict(id: Long) {
        conflicts.remove(id)
    }

    override fun getState(key: String): String? = state[key]

    override fun setState(key: String, value: String?) {
        if (value == null) state.remove(key) else state[key] = value
    }

    override fun touchPair(pairId: Long, ts: Long) {
        pairsById[pairId]?.let { pairsById[pairId] = it.copy(lastSyncAt = ts) }
    }

    override fun log(ts: Long, level: String, tag: String, msg: String) {
        logLines += "$level $tag: $msg"
    }

    private fun key(pairId: Long, relPath: String): String = "$pairId/$relPath"
}
