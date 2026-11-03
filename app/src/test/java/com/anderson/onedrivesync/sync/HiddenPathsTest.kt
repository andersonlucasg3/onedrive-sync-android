package com.anderson.onedrivesync.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenPathsTest {
    @Test
    fun `a name is hidden only when it starts with a dot`() {
        assertTrue(isHiddenName(".nomedia"))
        assertTrue(isHiddenName(".thumbnails"))
        assertTrue(isHiddenName(".git"))
        assertTrue(isHiddenName("."))
        assertFalse(isHiddenName("nomedia"))
        assertFalse(isHiddenName("a.nomedia"))
        assertFalse(isHiddenName("foto.jpg"))
        assertFalse(isHiddenName(""))
    }

    @Test
    fun `an empty path is visible`() {
        assertFalse(isHiddenRelPath(""))
    }

    @Test
    fun `a one level path is hidden when its only segment is`() {
        assertTrue(isHiddenRelPath(".nomedia"))
        assertTrue(isHiddenRelPath(".thumbnails"))
        assertFalse(isHiddenRelPath("foto.jpg"))
        assertFalse(isHiddenRelPath("Pasta"))
    }

    @Test
    fun `a path is hidden when any segment of it is`() {
        assertTrue(isHiddenRelPath(".thumbnails/x.jpg"))
        assertTrue(isHiddenRelPath("WhatsApp/.trash"))
        assertTrue(isHiddenRelPath("WhatsApp/.trash/foto.jpg"))
        assertTrue(isHiddenRelPath("a/.b/c"))
        // `.` cannot come from a real relative path (FileLocalFs rejects it); as a segment it is
        // still a name starting with a dot, so the conservative answer is hidden.
        assertTrue(isHiddenRelPath("./a"))
    }

    @Test
    fun `a path with only visible segments is visible at any depth`() {
        assertFalse(isHiddenRelPath("WhatsApp/Media/foto.jpg"))
        assertFalse(isHiddenRelPath("a/b"))
        assertFalse(isHiddenRelPath("a//b"))
        assertFalse(isHiddenRelPath("Pasta Céu/Ação & Cia/Relatório final.pdf"))
    }
}
