package com.anderson.onedrivesync.sync

import com.anderson.onedrivesync.graph.DriveItem
import com.anderson.onedrivesync.graph.FileFacet
import com.anderson.onedrivesync.graph.FileHashes
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncModelsTest {
    @Test
    fun `remoteItemOf decodes the base64 hash and agrees with the local hash`() {
        val bytes = "{}\n".toByteArray()
        val dto = DriveItem(
            id = "i1",
            name = "a.txt",
            size = bytes.size.toLong(),
            file = FileFacet(hashes = FileHashes(quickXorHash = "e+iDAgAAAAAAAAAAAwAAAAAAAAA=")),
        )

        val item = remoteItemOf(dto)

        assertEquals("7be8830200000000000000000300000000000000", item.quickXorHash)
        assertEquals(hashBytes(bytes), item.quickXorHash)
    }

    @Test
    fun `remoteItemOf normalises a hash that already arrived as hex`() {
        val dto = DriveItem(
            id = "i1",
            name = "a.txt",
            file = FileFacet(hashes = FileHashes(quickXorHash = "7BE8830200000000000000000300000000000000")),
        )

        assertEquals("7be8830200000000000000000300000000000000", remoteItemOf(dto).quickXorHash)
    }
}
