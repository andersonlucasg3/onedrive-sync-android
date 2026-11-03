package com.anderson.onedrivesync.db

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

object PairsDao {
    private const val TABLE = "pairs"

    fun list(db: SQLiteDatabase): List<Pair> =
        db.query(TABLE, null, null, null, null, null, "created_at ASC, id ASC").use { c ->
            buildList { while (c.moveToNext()) add(c.toPair()) }
        }

    fun get(db: SQLiteDatabase, id: Long): Pair? =
        db.query(TABLE, null, "id = ?", arrayOf(id.toString()), null, null, null).use { c ->
            if (c.moveToFirst()) c.toPair() else null
        }

    fun insert(
        db: SQLiteDatabase,
        remoteId: String,
        remotePath: String,
        localPath: String,
        displayName: String,
    ): Long = db.insertOrThrow(
        TABLE,
        null,
        ContentValues().apply {
            put("remote_id", remoteId)
            put("remote_path", remotePath)
            put("local_path", localPath)
            put("display_name", displayName)
            put("enabled", 1)
            put("created_at", System.currentTimeMillis())
            putNull("last_sync_at")
        },
    )

    fun updateLastSync(db: SQLiteDatabase, id: Long, ts: Long) {
        db.update(TABLE, ContentValues().apply { put("last_sync_at", ts) }, "id = ?", arrayOf(id.toString()))
    }

    fun setEnabled(db: SQLiteDatabase, id: Long, enabled: Boolean) {
        val values = ContentValues().apply { put("enabled", if (enabled) 1 else 0) }
        db.update(TABLE, values, "id = ?", arrayOf(id.toString()))
    }

    /** Deletes the pair and its entries, pending ops and conflicts (manual cascade). */
    fun delete(db: SQLiteDatabase, id: Long) {
        val args = arrayOf(id.toString())
        db.delete("entries", "pair_id = ?", args)
        db.delete("pending", "pair_id = ?", args)
        db.delete("conflicts", "pair_id = ?", args)
        db.delete(TABLE, "id = ?", args)
    }
}

private fun Cursor.toPair(): Pair = Pair(
    id = getLong(getColumnIndexOrThrow("id")),
    remoteId = getString(getColumnIndexOrThrow("remote_id")).orEmpty(),
    remotePath = getString(getColumnIndexOrThrow("remote_path")).orEmpty(),
    localPath = getString(getColumnIndexOrThrow("local_path")).orEmpty(),
    displayName = getString(getColumnIndexOrThrow("display_name")).orEmpty(),
    enabled = getInt(getColumnIndexOrThrow("enabled")) != 0,
    createdAt = getLong(getColumnIndexOrThrow("created_at")),
    lastSyncAt = longOrNull("last_sync_at"),
)

private fun Cursor.longOrNull(column: String): Long? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getLong(index)
}
