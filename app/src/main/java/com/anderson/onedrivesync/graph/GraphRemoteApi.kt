package com.anderson.onedrivesync.graph

import com.anderson.onedrivesync.sync.DeltaPage
import com.anderson.onedrivesync.sync.RemoteApi
import com.anderson.onedrivesync.sync.RemoteItem
import com.anderson.onedrivesync.sync.remoteItemOf
import java.io.File

/**
 * [RemoteApi] over [GraphClient].
 *
 * The stored cursor is either a full `@odata.deltaLink`/`@odata.nextLink` URL (passed through as
 * is) or null for a fresh enumeration; `GraphClient` owns token refresh and 429 retries. A null
 * `rootId` enumerates the drive root; any other id enumerates that folder.
 */
class GraphRemoteApi(
    private val client: GraphClient,
    private val log: (String) -> Unit = {},
) : RemoteApi {
    override suspend fun deltaPage(rootId: String?, cursor: String?): DeltaPage = call("delta") {
        val page = if (rootId == null) {
            client.deltaRoot(cursor)
        } else {
            client.deltaItem(rootId, cursor)
        }
        DeltaPage(
            items = page.value.map(::remoteItemOf),
            nextLink = page.nextLink,
            deltaLink = page.deltaLink,
        )
    }

    override suspend fun download(remoteId: String, dest: File, onProgress: (Long, Long) -> Unit) {
        call("download $remoteId") { client.download(remoteId, dest, onProgress) }
    }

    override suspend fun upload(
        parentRemoteId: String,
        name: String,
        file: File,
        onProgress: (Long, Long) -> Unit,
    ): RemoteItem = call("upload $name") {
        val size = file.length()
        val item = if (size < SMALL_UPLOAD_LIMIT) {
            client.uploadSmall(parentRemoteId, name, file).also { onProgress(size, size) }
        } else {
            client.uploadLarge(parentRemoteId, name, file, onProgress)
        }
        remoteItemOf(item)
    }

    override suspend fun createFolder(parentRemoteId: String, name: String): RemoteItem =
        call("criar pasta $name") { remoteItemOf(client.createFolder(parentRemoteId, name)) }

    override suspend fun move(remoteId: String, newParentRemoteId: String, newName: String): RemoteItem =
        call("mover $remoteId") { remoteItemOf(client.move(remoteId, newParentRemoteId, newName)) }

    override suspend fun delete(remoteId: String) {
        call("excluir $remoteId") { client.delete(remoteId) }
    }

    override suspend fun setMtime(remoteId: String, mtimeMs: Long) {
        call("mtime $remoteId") { client.setMtime(remoteId, mtimeMs) }
    }

    override suspend fun getItem(remoteId: String): RemoteItem =
        call("item $remoteId") { remoteItemOf(client.getItem(remoteId)) }

    /** Status plus Graph error code on failure; the response body is never logged. */
    private suspend fun <T> call(op: String, block: suspend () -> T): T = try {
        block()
    } catch (e: GraphException) {
        log("$op falhou: HTTP ${e.status}" + (e.code?.let { " ($it)" } ?: ""))
        throw e
    }

    private companion object {
        /** Graph simple upload limit; above it an upload session is required. */
        const val SMALL_UPLOAD_LIMIT = 4L * 1024 * 1024
    }
}
