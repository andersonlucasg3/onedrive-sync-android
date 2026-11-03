package com.anderson.onedrivesync.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Outcome of one finished sync cycle, persisted in the existing `state` key/value table so the UI
 * can explain what happened even after the process restarts.
 *
 * [ok] means no action failed and no fatal error aborted the cycle. Queued deletions and unresolved
 * conflicts are expected user work, not failures.
 */
@Serializable
data class CycleResult(
    val finishedAt: Long,
    val ok: Boolean,
    val downloaded: Int,
    val uploaded: Int,
    val failed: Int,
    val pending: Int,
    val conflicts: Int,
    val error: String? = null,
)

/** `state` keys: the last cycle as a whole and the last cycle of each pair. */
object CycleResultKeys {
    const val GLOBAL = "cycle.last"

    fun pair(pairId: Long): String = "cycle.last.$pairId"
}

private val cycleJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

fun encodeCycleResult(result: CycleResult): String =
    cycleJson.encodeToString(CycleResult.serializer(), result)

/** Tolerant by design: a missing key, a legacy value or a truncated row must never crash the UI. */
fun decodeCycleResult(raw: String?): CycleResult? {
    if (raw.isNullOrBlank()) return null
    return try {
        cycleJson.decodeFromString(CycleResult.serializer(), raw)
    } catch (e: IllegalArgumentException) {
        null
    }
}
