package com.anderson.onedrivesync.db

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

object ConflictsDao {
    private const val TABLE = "conflicts"

    fun insert(db: SQLiteDatabase, conflict: ConflictRow): Long = db.insertOrThrow(
        TABLE,
        null,
        ContentValues().apply {
            put("pair_id", conflict.pairId)
            put("rel_path", conflict.relPath)
            put("remote_id", conflict.remoteId)
            put("local_mtime", conflict.localMtimeMs)
            put("remote_mtime", conflict.remoteMtimeMs)
            put("local_size", conflict.localSize)
            put("remote_size", conflict.remoteSize)
            put("decision", conflict.decision)
            put("created_at", conflict.createdAt)
        },
    )

    fun list(db: SQLiteDatabase): List<ConflictRow> =
        db.query(TABLE, null, null, null, null, null, "created_at ASC, id ASC").use { c ->
            buildList { while (c.moveToNext()) add(c.toConflictRow()) }
        }

    fun get(db: SQLiteDatabase, id: Long): ConflictRow? =
        db.query(TABLE, null, "id = ?", arrayOf(id.toString()), null, null, null).use { c ->
            if (c.moveToFirst()) c.toConflictRow() else null
        }

    fun setDecision(db: SQLiteDatabase, id: Long, decision: String?) {
        db.update(TABLE, ContentValues().apply { put("decision", decision) }, "id = ?", arrayOf(id.toString()))
    }

    fun delete(db: SQLiteDatabase, id: Long) {
        db.delete(TABLE, "id = ?", arrayOf(id.toString()))
    }
}

private fun Cursor.toConflictRow(): ConflictRow = ConflictRow(
    id = getLong(getColumnIndexOrThrow("id")),
    pairId = getLong(getColumnIndexOrThrow("pair_id")),
    relPath = getString(getColumnIndexOrThrow("rel_path")).orEmpty(),
    remoteId = getString(getColumnIndexOrThrow("remote_id")),
    localMtimeMs = getLong(getColumnIndexOrThrow("local_mtime")),
    remoteMtimeMs = getLong(getColumnIndexOrThrow("remote_mtime")),
    localSize = getLong(getColumnIndexOrThrow("local_size")),
    remoteSize = getLong(getColumnIndexOrThrow("remote_size")),
    decision = getString(getColumnIndexOrThrow("decision")),
    createdAt = getLong(getColumnIndexOrThrow("created_at")),
)
