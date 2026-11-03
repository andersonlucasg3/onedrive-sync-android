package com.anderson.onedrivesync.db

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

object PendingDao {
    private const val TABLE = "pending"

    fun insert(db: SQLiteDatabase, op: PendingOp): Long = db.insertOrThrow(
        TABLE,
        null,
        ContentValues().apply {
            put("pair_id", op.pairId)
            put("kind", op.kind.name)
            put("rel_path", op.relPath)
            put("remote_id", op.remoteId)
            put("detail", op.detail)
            put("created_at", op.createdAt)
        },
    )

    fun list(db: SQLiteDatabase): List<PendingOp> =
        db.query(TABLE, null, null, null, null, null, "created_at ASC, id ASC").use { c ->
            buildList { while (c.moveToNext()) add(c.toPendingOp()) }
        }

    fun listByPair(db: SQLiteDatabase, pairId: Long): List<PendingOp> =
        db.query(TABLE, null, "pair_id = ?", arrayOf(pairId.toString()), null, null, "created_at ASC, id ASC")
            .use { c -> buildList { while (c.moveToNext()) add(c.toPendingOp()) } }

    fun get(db: SQLiteDatabase, id: Long): PendingOp? =
        db.query(TABLE, null, "id = ?", arrayOf(id.toString()), null, null, null).use { c ->
            if (c.moveToFirst()) c.toPendingOp() else null
        }

    fun delete(db: SQLiteDatabase, id: Long) {
        db.delete(TABLE, "id = ?", arrayOf(id.toString()))
    }

    /** True when the same operation is already queued (avoids duplicates across cycles). */
    fun exists(db: SQLiteDatabase, pairId: Long, kind: PendingKind, relPath: String): Boolean =
        db.rawQuery(
            "SELECT 1 FROM pending WHERE pair_id = ? AND kind = ? AND rel_path = ? LIMIT 1",
            arrayOf(pairId.toString(), kind.name, relPath),
        ).use { c -> c.moveToFirst() }
}

private fun Cursor.toPendingOp(): PendingOp = PendingOp(
    id = getLong(getColumnIndexOrThrow("id")),
    pairId = getLong(getColumnIndexOrThrow("pair_id")),
    kind = PendingKind.valueOf(getString(getColumnIndexOrThrow("kind")).orEmpty()),
    relPath = getString(getColumnIndexOrThrow("rel_path")).orEmpty(),
    remoteId = getString(getColumnIndexOrThrow("remote_id")),
    detail = getString(getColumnIndexOrThrow("detail")),
    createdAt = getLong(getColumnIndexOrThrow("created_at")),
)
