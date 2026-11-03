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
 * [deltaPage] takes `null` for a fresh enumeration and a `nextLink`/`deltaLink` returned by a
 * previous page otherwise.
 */
interface RemoteApi {
    suspend fun deltaPage(cursor: String?): DeltaPage
    suspend fun download(remoteId: String, dest: File, onProgress: (Long, Long) -> Unit)
    suspend fun upload(parentRemoteId: String, name: String, file: File, onProgress: (Long, Long) -> Unit): RemoteItem
    suspend fun createFolder(parentRemoteId: String, name: String): RemoteItem
    suspend fun move(remoteId: String, newParentRemoteId: String, newName: String): RemoteItem
    suspend fun delete(remoteId: String)
    suspend fun setMtime(remoteId: String, mtimeMs: Long)
    suspend fun getItem(remoteId: String): RemoteItem
}
