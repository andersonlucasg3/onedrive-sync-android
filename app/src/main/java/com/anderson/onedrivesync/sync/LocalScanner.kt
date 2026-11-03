package com.anderson.onedrivesync.sync

import com.anderson.onedrivesync.db.LocalFile
import java.io.File
import java.nio.file.Files

/**
 * Deterministic recursive walk of a pair's local root.
 * Skips in-progress `.part` entries and symlinks, never follows links, and tolerates
 * unreadable directories (skips them). Hash is left to the caller.
 */
object LocalScanner {
    private const val PART_SUFFIX = ".part"

    fun scan(root: File): List<LocalFile> {
        if (!root.isDirectory || Files.isSymbolicLink(root.toPath())) return emptyList()
        return buildList { walk(root, "", this) }
    }

    private fun walk(dir: File, prefix: String, out: MutableList<LocalFile>) {
        val children = dir.listFiles() ?: return
        for (child in children.sortedBy { it.name }) {
            if (child.name.endsWith(PART_SUFFIX) || Files.isSymbolicLink(child.toPath())) continue
            val relPath = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
            if (child.isDirectory) {
                out += LocalFile(relPath = relPath, isDir = true, size = 0, mtimeMs = 0, file = child)
                walk(child, relPath, out)
            } else {
                out += LocalFile(
                    relPath = relPath,
                    isDir = false,
                    size = child.length(),
                    mtimeMs = child.lastModified(),
                    file = child,
                )
            }
        }
    }
}
