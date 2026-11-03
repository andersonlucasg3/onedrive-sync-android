package com.anderson.onedrivesync.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class Db(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE pairs(
                id INTEGER PRIMARY KEY,
                remote_id TEXT,
                remote_path TEXT,
                local_path TEXT,
                display_name TEXT,
                enabled INT,
                created_at INT,
                last_sync_at INT
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE TABLE state(key TEXT PRIMARY KEY, value TEXT)")
        db.execSQL(
            """
            CREATE TABLE entries(
                id INTEGER PRIMARY KEY,
                pair_id INT,
                rel_path TEXT,
                parent_rel TEXT,
                name TEXT,
                is_dir INT,
                size INT,
                mtime INT,
                local_hash TEXT,
                remote_id TEXT,
                remote_etag TEXT,
                remote_hash TEXT,
                synced_at INT,
                state TEXT,
                UNIQUE(pair_id, rel_path)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE pending(
                id INTEGER PRIMARY KEY,
                pair_id INT,
                kind TEXT,
                rel_path TEXT,
                remote_id TEXT,
                detail TEXT,
                created_at INT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE conflicts(
                id INTEGER PRIMARY KEY,
                pair_id INT,
                rel_path TEXT,
                remote_id TEXT,
                local_mtime INT,
                remote_mtime INT,
                local_size INT,
                remote_size INT,
                decision TEXT,
                created_at INT
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE TABLE log(id INTEGER PRIMARY KEY, ts INT, level TEXT, tag TEXT, msg TEXT)")

        db.execSQL("CREATE INDEX idx_entries_pair_id ON entries(pair_id)")
        db.execSQL("CREATE INDEX idx_entries_remote_id ON entries(remote_id)")
        db.execSQL("CREATE INDEX idx_pending_pair_id ON pending(pair_id)")
        db.execSQL("CREATE INDEX idx_log_ts ON log(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    private companion object {
        const val DB_NAME = "onedrive.db"
        const val VERSION = 1
    }
}
