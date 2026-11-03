package com.anderson.onedrivesync.ui.vm

import com.anderson.onedrivesync.sync.CycleResult
import com.anderson.onedrivesync.sync.SyncState
import com.anderson.onedrivesync.ui.util.relativeTime

/**
 * The one thing the pair screen says about sync. The fixed panel at the top reads this; the cards
 * carry no live state of their own.
 */
sealed interface SyncStatus {
    /** No cycle was recorded yet. [lastSyncAt] keeps the legacy pair that synced before results. */
    data class Never(val lastSyncAt: Long?) : SyncStatus

    data class Running(
        val pairName: String?,
        val step: String,
        val done: Int,
        val total: Int,
        val phase: Int,
        val phases: Int,
        val startedAt: Long,
    ) : SyncStatus

    data class Done(val result: CycleResult) : SyncStatus

    data class Failed(val result: CycleResult?) : SyncStatus
}

/**
 * Pure mapping from the two observable sources to the panel state.
 *
 * Precedence: a running cycle wins over everything (the persisted result belongs to the previous
 * cycle), then a failed last cycle, then a successful one, then "never". A cycle reporting `ok` but
 * with failed actions is still a failure.
 *
 * [now] is the moment the running state was observed; the caller pins it (see the panel) so the
 * elapsed label does not restart on every progress emission.
 */
fun syncStatus(
    running: SyncState.Running?,
    global: CycleResult?,
    lastSyncAt: Long?,
    now: Long,
): SyncStatus {
    if (running != null) {
        return SyncStatus.Running(
            pairName = running.pairName.takeIf { it.isNotBlank() },
            step = running.step,
            done = running.done,
            total = running.total,
            phase = running.phase,
            phases = running.phases,
            startedAt = now,
        )
    }
    if (global != null && (!global.ok || global.failed > 0 || global.error != null)) {
        return SyncStatus.Failed(global)
    }
    if (global != null) return SyncStatus.Done(global)
    return SyncStatus.Never(lastSyncAt)
}

/**
 * "Etapa 2 de 5 · lendo OneDrive" while running (just the step when the caller numbers no phase);
 * a fixed sentence for the other states.
 */
fun statusHeadline(status: SyncStatus, now: Long): String = when (status) {
    is SyncStatus.Running -> {
        // A delta step carries its counters after " · " ("lendo OneDrive · página 3 · 600 itens");
        // those belong to [statusDetail], so the headline keeps only the phase name.
        val stepName = status.step.substringBefore(" · ").trim().ifEmpty { status.step }
        val phaseLabel =
            if (status.phase > 0 && status.phases > 0) "Etapa ${status.phase} de ${status.phases}" else null
        listOfNotNull(phaseLabel, stepName).joinToString(" · ")
    }

    is SyncStatus.Done -> "Tudo sincronizado"
    is SyncStatus.Failed -> "Último ciclo falhou"
    is SyncStatus.Never -> "Nunca sincronizado"
}

/**
 * Second line of the panel. Running: the counters the step already carries ("página 3 · 600 itens")
 * or "3/10", empty when there is no counter at all. Done: "há 5 min · 12 baixados". Failed: the
 * first line of the error or "N ações falharam".
 */
fun statusDetail(status: SyncStatus, now: Long): String = when (status) {
    is SyncStatus.Running -> {
        val counters = status.step.substringAfter(" · ", "").trim()
        when {
            counters.isNotEmpty() -> counters
            status.total > 0 -> "${status.done}/${status.total}"
            else -> ""
        }
    }

    is SyncStatus.Done -> "${relativeTime(now, status.result.finishedAt)} · ${cycleSummaryText(status.result)}"
    is SyncStatus.Failed -> firstErrorLine(status.result) ?: failuresText(status.result?.failed ?: 0)
    is SyncStatus.Never -> "Toque em Sincronizar para começar"
}

/**
 * The operation the executor is running right now, on one line:
 * "WhatsApp Images · enviando WhatsApp/x.jpg · 161/1.962".
 *
 * The executor label already carries the verb in the infinitive ("enviar x"), so it is normalized
 * to the gerund instead of repeating a prefix. Only file operations get a line: while the cycle is
 * reading the drive, scanning, hashing or planning there is no operation in progress and the panel
 * shows nothing (null), so the line takes no space outside [SyncStatus.Running].
 */
fun activeOperationText(status: SyncStatus): String? {
    if (status !is SyncStatus.Running) return null
    val operation = activeOperation(status.step.trim()) ?: return null
    val counter =
        if (status.total > 0) "${formatCount(status.done)}/${formatCount(status.total)}" else null
    val pair = status.pairName?.takeIf { it.isNotBlank() }
    return listOfNotNull(pair, operation, counter).joinToString(" · ")
}

/** "enviando WhatsApp/x.jpg": the executor verb changed to the gerund, path kept verbatim. */
private fun activeOperation(step: String): String? {
    for ((infinitive, gerund) in ACTIVE_OPERATION_VERBS) {
        val rest = when {
            step == infinitive -> ""
            step.startsWith("$infinitive ") -> step.removePrefix(infinitive)
            else -> continue
        }
        return gerund + rest
    }
    return null
}

/** Verbs the executor emits during the action phase, and the form the panel shows. */
private val ACTIVE_OPERATION_VERBS = listOf(
    "enviar" to "enviando",
    "baixar" to "baixando",
    "mover" to "movendo",
)

/** "1.962": the app is pt-BR, so thousands are grouped with a dot, as the panel example shows. */
private fun formatCount(value: Int): String {
    val digits = value.coerceAtLeast(0).toString()
    if (digits.length <= 3) return digits
    val text = StringBuilder(digits.length + digits.length / 3)
    for ((index, digit) in digits.withIndex()) {
        if (index > 0 && (digits.length - index) % 3 == 0) text.append('.')
        text.append(digit)
    }
    return text.toString()
}

/**
 * Card line for one pair: "há 5 min · 12 arquivos". With a persisted result the count is what that
 * cycle moved ("Nenhuma alteração" when it moved nothing); a pair that synced before results were
 * stored only has the timestamp. Null when the pair never synced.
 */
fun pairLastSyncText(result: CycleResult?, lastSyncAt: Long?, now: Long): String? {
    val finishedAt = result?.finishedAt ?: lastSyncAt ?: return null
    val time = relativeTime(now, finishedAt)
    if (result == null) return time
    val files = result.downloaded + result.uploaded
    val count = when {
        files == 0 -> "Nenhuma alteração"
        files == 1 -> "1 arquivo"
        else -> "$files arquivos"
    }
    return "$time · $count"
}

/** "1 baixado", "2 baixados, 1 enviado"; no transfers at all becomes "Nenhuma alteração". */
private fun cycleSummaryText(result: CycleResult): String {
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
private fun firstErrorLine(result: CycleResult?): String? =
    result?.error?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

private fun failuresText(count: Int): String = when {
    count <= 0 -> ""
    count == 1 -> "1 ação falhou"
    else -> "$count ações falharam"
}

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
