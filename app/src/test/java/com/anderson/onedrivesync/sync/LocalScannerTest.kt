package com.anderson.onedrivesync.sync

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class LocalScannerTest {
    @Test
    fun `scan returns sorted rel paths and includes directories`() {
        val root = Files.createTempDirectory("scanner").toFile()
        try {
            File(root, "b.txt").writeText("b")
            File(root, "a").mkdir()
            File(root, "a/z.txt").writeText("z")
            File(root, "a/m.txt").writeText("m")
            File(root, "z.txt").writeText("z")
            File(root, "c.part").writeText("in progress")
            File(root, ".hidden.part").writeText("in progress")

            val entries = LocalScanner.scan(root)

            assertEquals(listOf("a", "a/m.txt", "a/z.txt", "b.txt", "z.txt"), entries.map { it.relPath })
            val dir = entries.first { it.relPath == "a" }
            assertTrue(dir.isDir)
            assertEquals(0L, dir.size)
            assertEquals(0L, dir.mtimeMs)
            assertTrue(dir.file.isDirectory)
            val file = entries.first { it.relPath == "a/m.txt" }
            assertFalse(file.isDir)
            assertEquals(1L, file.size)
            assertTrue(file.file.isFile)
            assertTrue(entries.none { it.relPath.endsWith(".part") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `scan skips symlinks without following them`() {
        val root = Files.createTempDirectory("scanner").toFile()
        try {
            val target = File(root, "real").apply { mkdir() }
            File(target, "f.txt").writeText("x")
            val dirLink = File(root, "dir-link")
            val fileLink = File(root, "file-link.txt")
            val linksCreated = runCatching {
                Files.createSymbolicLink(dirLink.toPath(), target.toPath())
                Files.createSymbolicLink(fileLink.toPath(), File(target, "f.txt").toPath())
            }.isSuccess
            assumeTrue("symlinks not supported on this OS/user", linksCreated)

            val entries = LocalScanner.scan(root)

            assertEquals(listOf("real", "real/f.txt"), entries.map { it.relPath })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `scan does not throw on an unreadable directory`() {
        val root = Files.createTempDirectory("scanner").toFile()
        val locked = File(root, "locked").apply { mkdir() }
        try {
            File(locked, "x.txt").writeText("x")
            File(root, "ok.txt").writeText("ok")
            locked.setReadable(false, false)
            assumeTrue("cannot revoke read permission on this OS/user", !locked.canRead())

            val entries = LocalScanner.scan(root)

            assertEquals(listOf("ok.txt"), entries.map { it.relPath })
        } finally {
            locked.setReadable(true, false)
            root.deleteRecursively()
        }
    }

    @Test
    fun `scan of a missing root returns empty`() {
        val root = Files.createTempDirectory("scanner").toFile()
        try {
            assertTrue(LocalScanner.scan(File(root, "missing")).isEmpty())
            assertTrue(LocalScanner.scan(File(root, "missing/nested")).isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}
