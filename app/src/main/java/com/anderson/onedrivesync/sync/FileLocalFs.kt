package com.anderson.onedrivesync.sync

import com.anderson.onedrivesync.db.LocalFile
import java.io.File

/**
 * [LocalFs] over `java.io.File` (the app has MANAGE_EXTERNAL_STORAGE, so no SAF).
 *
 * Relative paths are normalised per segment. `..`, `.` and absolute paths are rejected so a remote
 * name can never escape [root]; characters OneDrive should never return (`\`, `:`) and empty names
 * are sanitised and reported through [log] instead of failing the whole cycle.
 */
class FileLocalFs(
    override val root: File,
    private val log: (String) -> Unit = {},
) : LocalFs {
    override fun file(relPath: String): File = resolve(relPath)

    override fun exists(relPath: String): Boolean = resolve(relPath).exists()

    override fun isDir(relPath: String): Boolean = resolve(relPath).isDirectory

    override fun mkdirs(relPath: String) {
        val dir = resolve(relPath)
        if (!dir.isDirectory) dir.mkdirs()
    }

    override fun delete(relPath: String): Boolean {
        val target = resolve(relPath)
        if (target == root) return false
        return if (target.isDirectory) target.deleteRecursively() else target.delete()
    }

    override fun move(from: String, to: String): Boolean {
        val source = resolve(from)
        val target = resolve(to)
        if (source == target) return source.exists()
        if (!source.exists()) return false
        target.parentFile?.mkdirs()
        // renameTo does not replace the target on every platform; callers use move to overwrite.
        if (target.exists() && !target.delete()) return false
        if (source.renameTo(target)) return true

        // Different mount points: renameTo fails, copy and delete instead.
        val copied = if (source.isDirectory) {
            source.copyRecursively(target, overwrite = true)
        } else {
            val mtime = source.lastModified()
            source.copyTo(target, overwrite = true).setLastModified(mtime)
            true
        }
        if (!copied) return false
        return if (source.isDirectory) source.deleteRecursively() else source.delete()
    }

    override fun scan(): List<LocalFile> = LocalScanner.scan(root)

    override fun hash(relPath: String): String = hashFile(resolve(relPath))

    private fun resolve(relPath: String): File {
        val raw = relPath.trim()
        if (raw.startsWith("/") || raw.startsWith("\\") || File(raw).isAbsolute) {
            throw IllegalArgumentException("absolute path not allowed: '$relPath'")
        }
        val trimmed = raw.trim('/')
        if (trimmed.isEmpty()) return root
        var target = root
        for (segment in trimmed.split('/')) {
            require(segment != ".." && segment != ".") { "unsafe rel path: '$relPath'" }
            target = File(target, safeName(segment, relPath))
        }
        return target
    }

    private fun safeName(segment: String, relPath: String): String {
        val sanitized = segment.replace('\\', '_').replace(':', '_').ifEmpty { "_" }
        if (sanitized != segment) log("nome inválido '$segment' em '$relPath' → '$sanitized'")
        return sanitized
    }
}
