package com.anderson.onedrivesync.sync

import com.anderson.onedrivesync.db.Entry

/**
 * Rebuilds one pair's remote tree from a delta cycle.
 *
 * Graph facts this relies on (see docs/ARCH.md): `parentReference.path` may be missing, so paths
 * come from the `parentId` chain up to the pair root; the same id may repeat (last occurrence wins);
 * a deleted folder does not return its children, so its known descendants are looked up in `prev`.
 * Unchanged ancestors also stay out of the delta, so their paths are seeded from `prev` as well.
 *
 * Items whose ancestry cannot be resolved are left out of [PairView.items]: the caller keeps the
 * previous index for them, they must never look like a deletion.
 */
object RemoteTree {
    private const val MAX_PASSES = 100

    fun resolve(remoteRootId: String, prev: List<Entry>, items: List<RemoteItem>): PairView {
        val byId = LinkedHashMap<String, RemoteItem>()
        for (item in items) byId[item.id] = item

        val deleted = deletedWithDescendants(prev, byId.values.filter { it.deleted }.map { it.id }.toSet())

        val paths = HashMap<String, String>(byId.size + prev.size)
        paths[remoteRootId] = ""
        for (entry in prev) {
            val id = entry.remoteId ?: continue
            if (id == remoteRootId || id in byId || id in deleted || entry.relPath.isEmpty()) continue
            paths[id] = entry.relPath
        }

        var pending = byId.values.filter { it.id != remoteRootId && it.id !in deleted }
        var pass = 0
        while (pending.isNotEmpty() && pass < MAX_PASSES) {
            val unresolved = ArrayList<RemoteItem>(pending.size)
            var progressed = false
            for (item in pending) {
                val parentPath = item.parentId?.takeIf { it != item.id }?.let { paths[it] }
                val name = item.name
                if (parentPath == null || name.isNullOrEmpty()) {
                    unresolved += item
                    continue
                }
                paths[item.id] = if (parentPath.isEmpty()) name else "$parentPath/$name"
                progressed = true
            }
            pending = unresolved
            pass++
            if (!progressed) break
        }

        val resolved = byId.values.mapNotNull { item ->
            if (item.id == remoteRootId) return@mapNotNull null
            paths[item.id]?.let { it to item }
        }.sortedWith(compareBy({ it.first }, { it.second.id }))

        // Different items claiming the same path (or paths differing only by case): keep the newest.
        val winners = LinkedHashMap<String, Pair<String, RemoteItem>>()
        val collisions = ArrayList<RemoteItem>()
        for (candidate in resolved) {
            val key = candidate.first.lowercase()
            val current = winners[key]
            when {
                current == null -> winners[key] = candidate
                candidate.second.mtimeMs > current.second.mtimeMs -> {
                    collisions += current.second
                    winners[key] = candidate
                }
                else -> collisions += candidate.second
            }
        }

        val out = LinkedHashMap<String, RemoteItem>(winners.size)
        for (winner in winners.values) out[winner.first] = winner.second
        return PairView(items = out, deletedIds = deleted, collisions = collisions)
    }

    /** Adds every indexed descendant of a deleted folder id; the delta does not report them. */
    private fun deletedWithDescendants(prev: List<Entry>, direct: Set<String>): Set<String> {
        if (direct.isEmpty()) return emptySet()
        val prevById = HashMap<String, Entry>(prev.size)
        for (entry in prev) entry.remoteId?.let { prevById.putIfAbsent(it, entry) }

        val deleted = LinkedHashSet(direct)
        val queue = ArrayDeque(direct)
        while (queue.isNotEmpty()) {
            val prefix = prevById[queue.removeFirst()]?.relPath ?: continue
            if (prefix.isEmpty()) continue // pair root itself; a full rescan decides, not a delta
            val childPrefix = "$prefix/"
            for (other in prev) {
                val id = other.remoteId ?: continue
                if (id in deleted) continue
                if (other.relPath == prefix || other.relPath.startsWith(childPrefix)) {
                    deleted += id
                    queue += id
                }
            }
        }
        return deleted
    }
}
