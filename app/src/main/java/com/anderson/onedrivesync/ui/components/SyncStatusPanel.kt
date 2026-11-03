package com.anderson.onedrivesync.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anderson.onedrivesync.R
import com.anderson.onedrivesync.sync.CycleResult
import com.anderson.onedrivesync.sync.SyncState
import com.anderson.onedrivesync.ui.vm.SyncStatus
import com.anderson.onedrivesync.ui.vm.activeOperationText
import com.anderson.onedrivesync.ui.vm.elapsedText
import com.anderson.onedrivesync.ui.vm.statusDetail
import com.anderson.onedrivesync.ui.vm.statusHeadline
import com.anderson.onedrivesync.ui.vm.syncStatus
import kotlinx.coroutines.delay

/** Green is not part of the (dynamic) color scheme, so a fixed pair of tones keeps "ok" readable. */
private val SyncOkGreenLight = Color(0xFF2E7D32)
private val SyncOkGreenDark = Color(0xFF81C784)

/**
 * The fixed item at the top of the pair list: the single place that shows the live cycle (phase,
 * progress, counters, elapsed time) and the outcome of the last one, plus the global actions.
 *
 * Kept outside the list on purpose: it must stay reachable while the pairs scroll.
 */
@Composable
fun SyncStatusPanel(
    syncState: SyncState,
    globalResult: CycleResult?,
    lastSyncAt: Long?,
    onSyncAll: () -> Unit,
    onCancel: () -> Unit,
    onOpenActivity: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val now = rememberNow()
    val running = syncState as? SyncState.Running
    // The engine does not stamp the start, so pin the first instant of this running episode: every
    // progress emission then counts from the same moment instead of restarting the elapsed label.
    val startedAt = remember(running != null) { System.currentTimeMillis() }
    val status = syncStatus(
        running = running,
        global = globalResult,
        lastSyncAt = lastSyncAt,
        now = if (running != null) startedAt else now,
    )
    val accent = when (status) {
        is SyncStatus.Done -> if (isSystemInDarkTheme()) SyncOkGreenDark else SyncOkGreenLight
        is SyncStatus.Failed -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val detail = when (status) {
        is SyncStatus.Running -> listOfNotNull(
            statusDetail(status, now).takeIf { it.isNotEmpty() },
            elapsedText(now - status.startedAt),
        ).joinToString(" · ")

        else -> statusDetail(status, now)
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = statusIcon(status), contentDescription = null, tint = accent)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                ) {
                    Text(
                        text = statusHeadline(status, now),
                        style = MaterialTheme.typography.titleMedium,
                        color = accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (detail.isNotEmpty()) {
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (status is SyncStatus.Running) {
                val operation = activeOperationText(status)
                if (operation != null) {
                    Text(
                        text = operation,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (status.total > 0) {
                    LinearProgressIndicator(
                        progress = { status.done.toFloat() / status.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (status) {
                    is SyncStatus.Running -> OutlinedButton(onClick = onCancel) {
                        Text(stringResource(R.string.common_cancel))
                    }

                    is SyncStatus.Failed -> {
                        Button(onClick = onSyncAll) {
                            Text(stringResource(R.string.common_try_again))
                        }
                        TextButton(onClick = onOpenActivity) {
                            Text(stringResource(R.string.pairs_view_activity))
                        }
                    }

                    else -> Button(onClick = onSyncAll) {
                        Text(stringResource(R.string.pairs_sync_all))
                    }
                }
            }
        }
    }
}

private fun statusIcon(status: SyncStatus): ImageVector = when (status) {
    is SyncStatus.Running -> Icons.Filled.Refresh
    is SyncStatus.Done -> Icons.Filled.CheckCircle
    is SyncStatus.Failed -> Icons.Outlined.Warning
    is SyncStatus.Never -> Icons.Outlined.CloudOff
}

/** Ticks once per second so the elapsed and relative labels stay honest while the panel is visible. */
@Composable
private fun rememberNow(): Long {
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }
    return nowMs
}
