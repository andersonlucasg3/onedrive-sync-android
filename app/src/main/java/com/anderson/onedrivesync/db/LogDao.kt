package com.anderson.onedrivesync.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

data class LogRow(val ts: Long, val level: String, val tag: String, val msg: String)

object LogDao {
    private const val TABLE = "log"

    fun insert(db: SQLiteDatabase, ts: Long, level: String, tag: String, msg: String) {
        db.insertOrThrow(
            TABLE,
            null,
            ContentValues().apply {
                put("ts", ts)
                put("level", level)
                put("tag", tag)
                put("msg", msg)
            },
        )
    }

    fun recent(db: SQLiteDatabase, limit: Int = 500): List<LogRow> =
        db.query(TABLE, null, null, null, null, null, "ts DESC, id DESC", limit.toString()).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        LogRow(
                            ts = c.getLong(c.getColumnIndexOrThrow("ts")),
                            level = c.getString(c.getColumnIndexOrThrow("level")).orEmpty(),
                            tag = c.getString(c.getColumnIndexOrThrow("tag")).orEmpty(),
                            msg = c.getString(c.getColumnIndexOrThrow("msg")).orEmpty(),
                        ),
                    )
                }
            }
        }

    /** Deletes every row ("Limpar log" in the activity screen). */
    fun clear(db: SQLiteDatabase) {
        db.delete(TABLE, null, null)
    }

    /** Row count, for the diagnostics summary ("tamanho do log"). */
    fun count(db: SQLiteDatabase): Int =
        db.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** Keeps the newest [keep] rows. */
    fun prune(db: SQLiteDatabase, keep: Int = 2000) {
        db.execSQL(
            "DELETE FROM log WHERE id NOT IN (SELECT id FROM log ORDER BY ts DESC, id DESC LIMIT ?)",
            arrayOf(keep),
        )
    }
}
