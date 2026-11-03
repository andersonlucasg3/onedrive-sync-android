package com.anderson.onedrivesync.ui.vm

import com.anderson.onedrivesync.sync.CycleResult
import com.anderson.onedrivesync.sync.SyncState

/** What the pair card can say about the last sync: exactly one of these, always. */
sealed interface PairProgress {
    data object Never : PairProgress

    data class Running(
        val step: String,
        val done: Int,
        val total: Int,
        val phase: Int = 0,
        val phases: Int = 0,
    ) : PairProgress

    data class Ok(val result: CycleResult) : PairProgress

    data class Failed(val result: CycleResult?) : PairProgress
}

/**
 * Pure mapping from the three observable sources to the card state.
 *
 * A running cycle wins over everything: the persisted result belongs to the previous cycle. A pair
 * that synced before this feature existed has [lastSyncAt] but no stored result, so it must not fall
 * back to "never"; it reports an empty successful result.
 */
fun pairProgress(lastSyncAt: Long?, result: CycleResult?, running: SyncState.Running?): PairProgress {
    if (running != null) {
        return PairProgress.Running(running.step, running.done, running.total, running.phase, running.phases)
    }
    if (result != null && (!result.ok || result.failed > 0 || result.error != null)) {
        return PairProgress.Failed(result)
    }
    if (result != null) return PairProgress.Ok(result)
    if (lastSyncAt != null) {
        return PairProgress.Ok(
            CycleResult(
                finishedAt = lastSyncAt,
                ok = true,
                downloaded = 0,
                uploaded = 0,
                failed = 0,
                pending = 0,
                conflicts = 0,
            ),
        )
    }
    return PairProgress.Never
}

/** "1 baixado", "2 baixados, 1 enviado"; no transfers at all becomes "Nenhuma alteração". */
fun cycleSummaryText(result: CycleResult): String {
    val parts = ArrayList<String>(2)
    if (result.downloaded > 0) {
        parts += if (result.downloaded == 1) "1 baixado" else "${result.downloaded} baixados"
    }
    if (result.uploaded > 0) {
        parts += if (result.uploaded == 1) "1 enviado" else "${result.uploaded} enviados"
    }
    return if (parts.isEmpty()) "Nenhuma alteração" else parts.joinToString(", ")
}

/** First line of a multi-line error, trimmed; null when there is no error text to show. */
fun firstErrorLine(result: CycleResult?): String? =
    result?.error?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

/**
 * "há 1m20s": how long the running cycle has been going. A delta has no total, so time is the only
 * honest evidence that the enumeration is still moving.
 */
fun elapsedText(elapsedMs: Long): String {
    val seconds = (elapsedMs.coerceAtLeast(0) / 1000).toInt()
    if (seconds < 60) return "há ${seconds}s"
    val minutes = seconds / 60
    val restSeconds = seconds % 60
    if (minutes < 60) {
        return if (restSeconds == 0) "há ${minutes}m" else "há ${minutes}m${restSeconds}s"
    }
    val hours = minutes / 60
    val restMinutes = minutes % 60
    return if (restMinutes == 0) "há ${hours}h" else "há ${hours}h${restMinutes.toString().padStart(2, '0')}m"
}
