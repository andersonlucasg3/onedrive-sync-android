package com.anderson.onedrivesync.sync

import com.anderson.onedrivesync.db.LocalFile
import java.io.File

/**
 * Local side of a pair: relative-path operations over a root directory. Kept free of Android APIs
 * so tests can point it at a real temporary directory.
 */
interface LocalFs {
    val root: File
    fun file(relPath: String): File
    fun exists(relPath: String): Boolean
    fun isDir(relPath: String): Boolean
    fun mkdirs(relPath: String)
    fun delete(relPath: String): Boolean
    fun move(from: String, to: String): Boolean
    fun scan(): List<LocalFile>

    /** QuickXorHash of one file, lowercase hex. */
    fun hash(relPath: String): String
}
