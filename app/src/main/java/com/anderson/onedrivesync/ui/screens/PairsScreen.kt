package com.anderson.onedrivesync.ui.screens

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
import androidx.compose.ui.res.stringResource
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
import com.anderson.onedrivesync.ui.components.SyncStatusPanel
import com.anderson.onedrivesync.ui.vm.PairRow
import com.anderson.onedrivesync.ui.vm.PairsViewModel
import com.anderson.onedrivesync.ui.vm.pairLastSyncText
import com.anderson.onedrivesync.work.SyncService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairsScreen(
    onNewPair: () -> Unit,
    onOpenOnboarding: () -> Unit,
    onOpenActivity: () -> Unit,
    viewModel: PairsViewModel = viewModel(factory = PairsViewModel.factory(LocalContext.current)),
) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val state by viewModel.state.collectAsStateWithLifecycle()
    val syncState by container.syncEngine.state.collectAsStateWithLifecycle()
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
    // the next poll tick, so the panel updates as soon as the notification disappears.
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
            // Sticky: outside the list, so the live state stays visible while the pairs scroll.
            if (!state.loading && state.rows.isNotEmpty()) {
                SyncStatusPanel(
                    syncState = syncState,
                    globalResult = state.globalResult,
                    lastSyncAt = state.rows.mapNotNull { row -> row.pair.lastSyncAt }.maxOrNull(),
                    onSyncAll = { SyncService.start(context) },
                    onCancel = { container.syncEngine.cancel() },
                    onOpenActivity = onOpenActivity,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
                )
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.rows, key = { row -> row.pair.id }) { row ->
                        PairCard(
                            row = row,
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
    onSync: () -> Unit,
    onToggleEnabled: () -> Unit,
    onDelete: () -> Unit,
) {
    val pair = row.pair
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

            LastSyncLine(result = row.result, lastSyncAt = pair.lastSyncAt)

            Button(onClick = onSync, enabled = pair.enabled) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.pairs_sync))
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

/** "Última sincronização: há 5 min · 12 arquivos", from this pair's persisted result only. */
@Composable
private fun LastSyncLine(result: CycleResult?, lastSyncAt: Long?) {
    val lastSync = pairLastSyncText(result, lastSyncAt, System.currentTimeMillis())
    Text(
        text = lastSync?.let { stringResource(R.string.pairs_last_sync, it) }
            ?: stringResource(R.string.pairs_status_never),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
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
