package com.anderson.onedrivesync.sync

import com.anderson.onedrivesync.graph.GraphException
import java.io.File

/**
 * In-memory OneDrive for tests: a tree of nodes plus a change log.
 *
 * The delta cursor is `"c:<n>"`, meaning "changes recorded from index n on". A call with `null`
 * enumerates the whole tree (like Graph's initial delta) and returns the cursor for the current
 * change count, so a later call only replays the mutations that happened after it. Mutations always
 * append a snapshot of the affected item, deletions append a tombstone (name empty, `deleted = true`)
 * and a folder deletion appends nothing about its descendants — exactly the Graph behaviour the
 * `RemoteTree` code relies on.
 */
class FakeRemoteApi(val rootId: String = ROOT) : RemoteApi {
    sealed interface Call {
        data class Delta(val cursor: String?) : Call
        data class Download(val id: String, val name: String) : Call
        data class Upload(val parentId: String, val name: String, val size: Int) : Call
        data class CreateFolder(val parentId: String, val name: String) : Call
        data class Move(val id: String, val parentId: String, val name: String) : Call
        data class Delete(val id: String) : Call
        data class SetMtime(val id: String, val mtimeMs: Long) : Call
        data class GetItem(val id: String) : Call
    }

    private class Node(
        val id: String,
        val parentId: String,
        val name: String,
        val isDir: Boolean,
        var bytes: ByteArray,
        var mtimeMs: Long,
    )

    private val nodes = LinkedHashMap<String, Node>()
    private val changes = ArrayList<RemoteItem>()
    private var nextId = 0

    val calls = ArrayList<Call>()

    /** Makes the next [deltaPage] fail with 410 Gone, as Graph does for an expired cursor. */
    var failNextDeltaWithGone = false

    private var failDownloadId: String? = null
    private var corruptDownloadId: String? = null
    private var truncateDownloadId: String? = null
    private var failCreateFolder = false

    /** Makes the next [download] of [remoteId] fail with a transient error, as a timeout would. */
    fun failNextDownload(remoteId: String) {
        failDownloadId = remoteId
    }

    /** Makes the next [download] of [remoteId] hand over wrong bytes of the same size. */
    fun corruptNextDownload(remoteId: String) {
        corruptDownloadId = remoteId
    }

    /** Makes the next [download] of [remoteId] hand over one byte less, as a cut transfer would. */
    fun truncateNextDownload(remoteId: String) {
        truncateDownloadId = remoteId
    }

    /** Makes the next [createFolder] answer 409, as when the folder was already created remotely. */
    fun failNextCreateFolder() {
        failCreateFolder = true
    }

    init {
        nodes[rootId] = Node(rootId, rootId, "", true, ByteArray(0), 0)
    }

    fun addFolder(path: String): String = create(path, isDir = true, bytes = ByteArray(0), mtimeMs = 0)

    fun addFile(path: String, content: String, mtimeMs: Long = 0): String =
        create(path, isDir = false, bytes = content.toByteArray(), mtimeMs = mtimeMs)

    fun has(path: String): Boolean = nodeAt(path) != null

    fun idOf(path: String): String = checkNotNull(nodeAt(path)) { "remote path not found: $path" }.id

    fun textOf(path: String): String = String(bytesOf(path))

    fun bytesOf(path: String): ByteArray = checkNotNull(nodeAt(path)) { "remote path not found: $path" }.bytes

    fun clearCalls() {
        calls.clear()
    }

    override suspend fun deltaPage(cursor: String?): DeltaPage {
        calls += Call.Delta(cursor)
        if (failNextDeltaWithGone) {
            failNextDeltaWithGone = false
            throw GraphException(status = 410, code = "resyncRequired", message = "delta token expired")
        }
        val start = cursor?.removePrefix(CURSOR_PREFIX)?.toIntOrNull()
        val items = if (start == null) nodes.values.map { it.toRemoteItem() } else changes.drop(start)
        return DeltaPage(items = items, nextLink = null, deltaLink = CURSOR_PREFIX + changes.size)
    }

    override suspend fun download(remoteId: String, dest: File, onProgress: (Long, Long) -> Unit) {
        val node = requireNode(remoteId)
        calls += Call.Download(remoteId, dest.name)
        if (failDownloadId == remoteId) {
            failDownloadId = null
            throw GraphException(status = 500, code = "generalException", message = "download failed")
        }
        var bytes = node.bytes
        if (corruptDownloadId == remoteId) {
            corruptDownloadId = null
            bytes = bytes.copyOf()
            if (bytes.isNotEmpty()) bytes[0] = (bytes[0].toInt() xor 0xFF).toByte()
        } else if (truncateDownloadId == remoteId) {
            truncateDownloadId = null
            bytes = bytes.copyOf((bytes.size - 1).coerceAtLeast(0))
        }
        dest.parentFile?.mkdirs()
        dest.writeBytes(bytes)
        onProgress(bytes.size.toLong(), bytes.size.toLong())
    }

    override suspend fun upload(
        parentRemoteId: String,
        name: String,
        file: File,
        onProgress: (Long, Long) -> Unit,
    ): RemoteItem {
        val bytes = file.readBytes()
        calls += Call.Upload(parentRemoteId, name, bytes.size)
        requireNode(parentRemoteId)
        val existing = nodes.values.firstOrNull { it.parentId == parentRemoteId && it.name == name && !it.isDir }
        val node = existing ?: Node("r${++nextId}", parentRemoteId, name, false, ByteArray(0), 0)
            .also { nodes[it.id] = it }
        node.bytes = bytes
        node.mtimeMs = file.lastModified()
        changes += node.toRemoteItem()
        onProgress(bytes.size.toLong(), bytes.size.toLong())
        return node.toRemoteItem()
    }

    override suspend fun createFolder(parentRemoteId: String, name: String): RemoteItem {
        calls += Call.CreateFolder(parentRemoteId, name)
        requireNode(parentRemoteId)
        if (failCreateFolder) {
            failCreateFolder = false
            throw GraphException(status = 409, code = "nameAlreadyExists", message = "name already exists")
        }
        val existing = nodes.values.firstOrNull { it.parentId == parentRemoteId && it.name == name && it.isDir }
        val node = existing ?: Node("r${++nextId}", parentRemoteId, name, true, ByteArray(0), 0)
            .also { nodes[it.id] = it }
        changes += node.toRemoteItem()
        return node.toRemoteItem()
    }

    override suspend fun move(remoteId: String, newParentRemoteId: String, newName: String): RemoteItem {
        calls += Call.Move(remoteId, newParentRemoteId, newName)
        return rename(remoteId, newParentRemoteId, newName)
    }

    override suspend fun delete(remoteId: String) {
        calls += Call.Delete(remoteId)
        remove(remoteId)
    }

    /** Test hook with the same effect as `move`, without going through the coroutine API. */
    fun rename(remoteId: String, newParentRemoteId: String, newName: String): RemoteItem {
        val node = requireNode(remoteId)
        requireNode(newParentRemoteId)
        val moved = Node(node.id, newParentRemoteId, newName, node.isDir, node.bytes, node.mtimeMs)
        nodes[remoteId] = moved
        changes += moved.toRemoteItem()
        return moved.toRemoteItem()
    }

    /** Test hook with the same effect as `delete`, without going through the coroutine API. */
    fun remove(remoteId: String) {
        val node = requireNode(remoteId)
        removeSubtree(remoteId)
        // Graph reports only the deleted node; its descendants vanish without a delta entry.
        changes += RemoteItem(
            id = node.id,
            name = "",
            parentId = node.parentId,
            isDir = node.isDir,
            size = 0,
            mtimeMs = 0,
            quickXorHash = null,
            deleted = true,
        )
    }

    override suspend fun setMtime(remoteId: String, mtimeMs: Long) {
        calls += Call.SetMtime(remoteId, mtimeMs)
        val node = requireNode(remoteId)
        node.mtimeMs = mtimeMs
        changes += node.toRemoteItem()
    }

    override suspend fun getItem(remoteId: String): RemoteItem {
        calls += Call.GetItem(remoteId)
        return requireNode(remoteId).toRemoteItem()
    }

    private fun create(path: String, isDir: Boolean, bytes: ByteArray, mtimeMs: Long): String {
        val parentPath = path.substringBeforeLast('/', "")
        val parentId = if (parentPath.isEmpty()) rootId else idOf(parentPath)
        val name = path.substringAfterLast('/')
        val existing = nodes.values.firstOrNull { it.parentId == parentId && it.name == name }
        val node = if (existing != null) {
            existing.bytes = bytes
            existing.mtimeMs = mtimeMs
            existing
        } else {
            Node("r${++nextId}", parentId, name, isDir, bytes, mtimeMs).also { nodes[it.id] = it }
        }
        changes += node.toRemoteItem()
        return node.id
    }

    private fun requireNode(remoteId: String): Node =
        nodes[remoteId] ?: throw GraphException(status = 404, code = "itemNotFound", message = "no item $remoteId")

    private fun nodeAt(path: String): Node? {
        var current: Node = nodes[rootId] ?: return null
        if (path.isEmpty()) return current
        for (segment in path.split('/')) {
            current = nodes.values.firstOrNull { it.parentId == current.id && it.name == segment } ?: return null
        }
        return current
    }

    private fun removeSubtree(remoteId: String) {
        for (child in nodes.values.filter { it.parentId == remoteId && it.id != rootId }) removeSubtree(child.id)
        nodes.remove(remoteId)
    }

    private fun Node.toRemoteItem(): RemoteItem = RemoteItem(
        id = id,
        name = name,
        parentId = parentId,
        isDir = isDir,
        size = bytes.size.toLong(),
        mtimeMs = mtimeMs,
        quickXorHash = if (isDir) null else hashBytes(bytes),
        deleted = false,
    )

    companion object {
        const val ROOT = "root"
        private const val CURSOR_PREFIX = "c:"
    }
}
