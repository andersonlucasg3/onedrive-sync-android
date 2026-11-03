package com.anderson.onedrivesync.ui.vm

import com.anderson.onedrivesync.sync.CycleResult
import com.anderson.onedrivesync.sync.SyncState

/** What the pair card can say about the last sync: exactly one of these, always. */
sealed interface PairProgress {
    data object Never : PairProgress

    data class Running(val step: String, val done: Int, val total: Int) : PairProgress

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
    if (running != null) return PairProgress.Running(running.step, running.done, running.total)
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
