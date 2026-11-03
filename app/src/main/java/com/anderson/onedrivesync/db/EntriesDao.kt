package com.anderson.onedrivesync.db

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

object EntriesDao {
    private const val TABLE = "entries"

    fun listByPair(db: SQLiteDatabase, pairId: Long): List<Entry> =
        db.query(TABLE, null, "pair_id = ?", arrayOf(pairId.toString()), null, null, "rel_path ASC").use { c ->
            buildList { while (c.moveToNext()) add(c.toEntry()) }
        }

    fun getByRelPath(db: SQLiteDatabase, pairId: Long, relPath: String): Entry? =
        db.query(TABLE, null, "pair_id = ? AND rel_path = ?", arrayOf(pairId.toString(), relPath), null, null, null)
            .use { c -> if (c.moveToFirst()) c.toEntry() else null }

    fun getByRemoteId(db: SQLiteDatabase, pairId: Long, remoteId: String): Entry? =
        db.query(TABLE, null, "pair_id = ? AND remote_id = ?", arrayOf(pairId.toString(), remoteId), null, null, null)
            .use { c -> if (c.moveToFirst()) c.toEntry() else null }

    /** Inserts the entry, or updates the row with the same (pair_id, rel_path) keeping its id. */
    fun upsert(db: SQLiteDatabase, entry: Entry) {
        val values = entry.toValues()
        val args = arrayOf(entry.pairId.toString(), entry.relPath)
        if (db.update(TABLE, values, "pair_id = ? AND rel_path = ?", args) == 0) {
            db.insertOrThrow(TABLE, null, values)
        }
    }

    fun deleteByRelPath(db: SQLiteDatabase, pairId: Long, relPath: String) {
        db.delete(TABLE, "pair_id = ? AND rel_path = ?", arrayOf(pairId.toString(), relPath))
    }

    fun deleteByPair(db: SQLiteDatabase, pairId: Long) {
        db.delete(TABLE, "pair_id = ?", arrayOf(pairId.toString()))
    }

    fun updateState(db: SQLiteDatabase, id: Long, state: EntryState) {
        db.update(TABLE, ContentValues().apply { put("state", state.name) }, "id = ?", arrayOf(id.toString()))
    }

    fun countByPair(db: SQLiteDatabase, pairId: Long): Int =
        db.rawQuery("SELECT COUNT(*) FROM entries WHERE pair_id = ?", arrayOf(pairId.toString())).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
}

private fun Entry.toValues(): ContentValues = ContentValues().apply {
    put("pair_id", pairId)
    put("rel_path", relPath)
    put("parent_rel", parentRel)
    put("name", name)
    put("is_dir", if (isDir) 1 else 0)
    put("size", size)
    put("mtime", mtimeMs)
    put("local_hash", localHash)
    put("remote_id", remoteId)
    put("remote_etag", remoteEtag)
    put("remote_hash", remoteHash)
    put("synced_at", syncedAt)
    put("state", state.name)
}

private fun Cursor.toEntry(): Entry = Entry(
    id = getLong(getColumnIndexOrThrow("id")),
    pairId = getLong(getColumnIndexOrThrow("pair_id")),
    relPath = getString(getColumnIndexOrThrow("rel_path")).orEmpty(),
    parentRel = getString(getColumnIndexOrThrow("parent_rel")),
    name = getString(getColumnIndexOrThrow("name")).orEmpty(),
    isDir = getInt(getColumnIndexOrThrow("is_dir")) != 0,
    size = getLong(getColumnIndexOrThrow("size")),
    mtimeMs = getLong(getColumnIndexOrThrow("mtime")),
    localHash = getString(getColumnIndexOrThrow("local_hash")),
    remoteId = getString(getColumnIndexOrThrow("remote_id")),
    remoteEtag = getString(getColumnIndexOrThrow("remote_etag")),
    remoteHash = getString(getColumnIndexOrThrow("remote_hash")),
    syncedAt = getLong(getColumnIndexOrThrow("synced_at")),
    state = EntryState.valueOf(getString(getColumnIndexOrThrow("state")).orEmpty()),
)
