package com.anderson.onedrivesync.ui.screens

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anderson.onedrivesync.App
import com.anderson.onedrivesync.R
import com.anderson.onedrivesync.sync.CycleResult
import com.anderson.onedrivesync.sync.SyncState
import com.anderson.onedrivesync.ui.components.EmptyState
import com.anderson.onedrivesync.ui.components.LoadingBox
import com.anderson.onedrivesync.ui.util.relativeTime
import com.anderson.onedrivesync.ui.vm.PairProgress
import com.anderson.onedrivesync.ui.vm.PairRow
import com.anderson.onedrivesync.ui.vm.PairsViewModel
import com.anderson.onedrivesync.ui.vm.cycleSummaryText
import com.anderson.onedrivesync.ui.vm.elapsedText
import com.anderson.onedrivesync.ui.vm.firstErrorLine
import com.anderson.onedrivesync.ui.vm.pairProgress
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairsScreen(
    onNewPair: () -> Unit,
    onOpenOnboarding: () -> Unit,
    onOpenActivity: () -> Unit,
    viewModel: PairsViewModel = viewModel(factory = PairsViewModel.factory(LocalContext.current)),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val syncState by (context.applicationContext as App).container.syncEngine.state
        .collectAsStateWithLifecycle()
    val running = syncState as? SyncState.Running
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.reload()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(viewModel) {
        viewModel.startPolling()
        onDispose { viewModel.stopPolling() }
    }

    // The cycle writes its result at the end; reload the moment it goes idle instead of waiting for
    // the next poll tick, so the card updates as soon as the notification disappears.
    LaunchedEffect(syncState) {
        if (syncState is SyncState.Idle) viewModel.reload()
    }

    LaunchedEffect(state.errorMessage) {
        val message = state.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeError()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pairs_title)) },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewPair,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.pairs_new)) },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (!state.storageGranted) {
                StorageBanner(onOpenOnboarding = onOpenOnboarding)
            }
            val failedCycle = state.globalResult?.takeIf { !it.ok || it.failed > 0 || it.error != null }
            when {
                running != null -> CycleRunningBanner(running = running)
                failedCycle != null -> CycleFailedBanner(result = failedCycle, onOpenActivity = onOpenActivity)
            }
            when {
                state.loading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    LoadingBox()
                }

                state.rows.isEmpty() -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        icon = Icons.Outlined.FolderOff,
                        title = stringResource(R.string.pairs_empty_title),
                        message = stringResource(R.string.pairs_empty_message),
                        actionLabel = stringResource(R.string.pairs_empty_action),
                        onAction = onNewPair,
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.rows, key = { row -> row.pair.id }) { row ->
                        PairCard(
                            row = row,
                            running = running?.takeIf { it.pairName == row.pair.displayName },
                            onSync = { viewModel.sync(row.pair.id) },
                            onToggleEnabled = { viewModel.setEnabled(row.pair.id, !row.pair.enabled) },
                            onDelete = { viewModel.delete(row.pair.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageBanner(onOpenOnboarding: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 12.dp, end = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(
                    text = stringResource(R.string.pairs_storage_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = stringResource(R.string.pairs_storage_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            TextButton(onClick = onOpenOnboarding) {
                Text(stringResource(R.string.pairs_storage_action))
            }
        }
    }
}

@Composable
private fun PairCard(
    row: PairRow,
    running: SyncState.Running?,
    onSync: () -> Unit,
    onToggleEnabled: () -> Unit,
    onDelete: () -> Unit,
) {
    val pair = row.pair
    val progress = pairProgress(pair.lastSyncAt, row.result, running)
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = pair.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!pair.enabled) {
                        Text(
                            text = stringResource(R.string.pairs_disabled),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.pairs_more),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (pair.enabled) R.string.pairs_disable else R.string.pairs_enable,
                                    ),
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onToggleEnabled()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.pairs_delete)) },
                            onClick = {
                                menuOpen = false
                                confirmDelete = true
                            },
                        )
                    }
                }
            }

            PathLine(path = pair.remotePath)
            PathLine(path = pair.localPath)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (row.pendingCount > 0) {
                    CountBadge(text = pendingLabel(row.pendingCount))
                }
                if (row.conflictCount > 0) {
                    CountBadge(
                        text = conflictLabel(row.conflictCount),
                        container = MaterialTheme.colorScheme.errorContainer,
                        content = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            PairProgressLine(progress = progress)

            Button(
                onClick = onSync,
                enabled = pair.enabled && progress !is PairProgress.Running,
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(
                        when (progress) {
                            PairProgress.Never -> R.string.pairs_sync_now
                            is PairProgress.Failed -> R.string.common_try_again
                            else -> R.string.pairs_sync
                        },
                    ),
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.pairs_delete_title)) },
            text = { Text(stringResource(R.string.pairs_delete_message, pair.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) {
                    Text(stringResource(R.string.pairs_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

/** Green is not part of the (dynamic) color scheme, so a fixed pair of tones keeps "ok" readable. */
private val SyncOkGreenLight = Color(0xFF2E7D32)
private val SyncOkGreenDark = Color(0xFF81C784)

@Composable
private fun PairProgressLine(progress: PairProgress) {
    when (progress) {
        PairProgress.Never -> Text(
            text = stringResource(R.string.pairs_status_never),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        is PairProgress.Running -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (progress.total > 0) {
                LinearProgressIndicator(
                    progress = { progress.done.toFloat() / progress.total },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            RunningText(
                step = progress.step,
                done = progress.done,
                total = progress.total,
                phase = progress.phase,
                phases = progress.phases,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is PairProgress.Ok -> Text(
            text = stringResource(
                R.string.pairs_status_ok,
                relativeTime(nowMs = System.currentTimeMillis(), thenMs = progress.result.finishedAt),
                cycleSummaryText(progress.result),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = if (isSystemInDarkTheme()) SyncOkGreenDark else SyncOkGreenLight,
        )

        is PairProgress.Failed -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.pairs_status_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            val detail = firstErrorLine(progress.result)
            val failed = progress.result?.failed ?: 0
            when {
                detail != null -> Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                failed > 0 -> Text(
                    text = pluralStringResource(R.plurals.pairs_cycle_failures, failed, failed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun CycleRunningBanner(running: SyncState.Running) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 12.dp, end = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        RunningText(
            step = running.step,
            done = running.done,
            total = running.total,
            phase = running.phase,
            phases = running.phases,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

/**
 * "Etapa 2 de 5 · lendo OneDrive · página 42 · 8.400 itens · há 1m20s".
 *
 * No delta has a total, so the text carries the phase number, the real counters and the elapsed
 * time: the bar stays indeterminate without pretending to know how much is left.
 */
@Composable
private fun RunningText(
    step: String,
    done: Int,
    total: Int,
    phase: Int,
    phases: Int,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val phaseLabel = if (phases > 0 && phase > 0) {
        stringResource(R.string.pairs_syncing_phase, phase, phases)
    } else {
        null
    }
    val stepLabel = if (total > 0) {
        stringResource(R.string.pairs_syncing_progress, step, done, total)
    } else {
        stringResource(R.string.pairs_syncing, step)
    }
    Text(
        text = listOfNotNull(phaseLabel, stepLabel, elapsedLabel()).joinToString(" · "),
        style = style,
        color = color,
        modifier = modifier,
    )
}

/** Ticks once per second while the caller is composed; leaving it (cycle end) cancels the ticker. */
@Composable
private fun elapsedLabel(): String {
    var elapsedMs by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        val started = System.currentTimeMillis()
        while (true) {
            elapsedMs = System.currentTimeMillis() - started
            delay(1_000)
        }
    }
    return elapsedText(elapsedMs)
}

@Composable
private fun CycleFailedBanner(result: CycleResult, onOpenActivity: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 12.dp, end = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(
                    text = stringResource(R.string.pairs_cycle_failed_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                val detail = firstErrorLine(result)
                val failed = result.failed
                when {
                    detail != null -> Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    failed > 0 -> Text(
                        text = pluralStringResource(R.plurals.pairs_cycle_failures, failed, failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            TextButton(onClick = onOpenActivity) {
                Text(stringResource(R.string.pairs_view_activity))
            }
        }
    }
}

@Composable
private fun PathLine(path: String) {
    Text(
        text = path,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun CountBadge(
    text: String,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(50)) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

private fun pendingLabel(count: Int): String =
    if (count == 1) "1 pendência" else "$count pendências"

private fun conflictLabel(count: Int): String =
    if (count == 1) "1 conflito" else "$count conflitos"
