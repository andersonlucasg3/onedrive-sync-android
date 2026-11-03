package com.anderson.onedrivesync.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

object StateDao {
    private const val TABLE = "state"

    fun get(db: SQLiteDatabase, key: String): String? =
        db.query(TABLE, arrayOf("value"), "key = ?", arrayOf(key), null, null, null).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    fun set(db: SQLiteDatabase, key: String, value: String) {
        val values = ContentValues().apply {
            put("key", key)
            put("value", value)
        }
        db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun remove(db: SQLiteDatabase, key: String) {
        db.delete(TABLE, "key = ?", arrayOf(key))
    }
}
