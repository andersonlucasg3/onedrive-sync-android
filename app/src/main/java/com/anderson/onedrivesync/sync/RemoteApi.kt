package com.anderson.onedrivesync.sync

import java.io.File

/** One delta page in domain terms: items plus the links that drive pagination. */
data class DeltaPage(
    val items: List<RemoteItem>,
    val nextLink: String? = null,
    val deltaLink: String? = null,
)

/**
 * Remote side of a pair. Implemented over Graph in production and by an in-memory tree in tests;
 * nothing here touches HTTP or Android.
 *
 * [deltaPage] scopes the enumeration: `rootId == null` is the whole drive, a remote item id is that
 * folder only. [cursor] is `null` for a fresh enumeration and a `nextLink`/`deltaLink` URL returned
 * by a previous page otherwise.
 */
interface RemoteApi {
    suspend fun deltaPage(rootId: String?, cursor: String?): DeltaPage
    suspend fun download(remoteId: String, dest: File, onProgress: (Long, Long) -> Unit)
    suspend fun upload(parentRemoteId: String, name: String, file: File, onProgress: (Long, Long) -> Unit): RemoteItem
    suspend fun createFolder(parentRemoteId: String, name: String): RemoteItem
    suspend fun move(remoteId: String, newParentRemoteId: String, newName: String): RemoteItem
    suspend fun delete(remoteId: String)
    suspend fun setMtime(remoteId: String, mtimeMs: Long)
    suspend fun getItem(remoteId: String): RemoteItem

    /**
     * The child of [parentId] named [name], or null when it does not exist. Unlike [getItem] this
     * needs no id, which is the point: it recovers the id of a folder that a failed create proved
     * to exist but the delta never reported.
     */
    suspend fun resolveChild(parentId: String, name: String): RemoteItem?
}
