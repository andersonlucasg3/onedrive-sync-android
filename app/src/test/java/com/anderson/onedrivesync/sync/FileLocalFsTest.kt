package com.anderson.onedrivesync.sync

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FileLocalFsTest {
    private lateinit var root: File
    private lateinit var fs: FileLocalFs

    @Before
    fun setUp() {
        root = Files.createTempDirectory("localfs").toFile()
        fs = FileLocalFs(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `rejects traversal and absolute paths`() {
        assertThrows(IllegalArgumentException::class.java) { fs.file("../escape.txt") }
        assertThrows(IllegalArgumentException::class.java) { fs.file("a/../../escape.txt") }
        assertThrows(IllegalArgumentException::class.java) { fs.file("/data/local/tmp/x.txt") }
        assertFalse(File(root.parentFile, "escape.txt").exists())
    }

    @Test
    fun `sanitizes invalid remote names and reports them`() {
        val logged = ArrayList<String>()
        val logging = FileLocalFs(root) { logged += it }

        assertEquals("a_b_c.txt", logging.file("a:b\\c.txt").name)
        assertEquals(File(File(File(root, "a"), "_"), "b.txt"), logging.file("a//b.txt"))
        assertEquals(2, logged.size)
    }

    @Test
    fun `move replaces the target and keeps the source mtime`() {
        val source = File(root, "a.txt").apply { writeText("a") }
        source.setLastModified(1_600_000_000_000)
        File(root, "b.txt").writeText("old")

        assertTrue(fs.move("a.txt", "b.txt"))

        assertEquals("a", File(root, "b.txt").readText())
        assertFalse(File(root, "a.txt").exists())
        assertEquals(1_600_000_000_000L, File(root, "b.txt").lastModified())
    }

    @Test
    fun `delete removes a directory recursively`() {
        File(root, "dir/sub").mkdirs()
        File(root, "dir/sub/x.txt").writeText("x")

        assertTrue(fs.delete("dir"))

        assertFalse(File(root, "dir").exists())
    }

    @Test
    fun `scan and hash use rel paths`() {
        File(root, "dir").mkdirs()
        File(root, "dir/a.txt").writeText("alfa")

        assertEquals(listOf("dir", "dir/a.txt"), fs.scan().map { it.relPath })
        assertEquals(hashBytes("alfa".toByteArray()), fs.hash("dir/a.txt"))
        assertTrue(fs.isDir("dir"))
        assertTrue(fs.exists("dir/a.txt"))
    }
}
