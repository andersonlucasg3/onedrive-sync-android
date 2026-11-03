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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anderson.onedrivesync.R
import com.anderson.onedrivesync.db.ConflictRow
import com.anderson.onedrivesync.db.PendingKind
import com.anderson.onedrivesync.db.PendingOp
import com.anderson.onedrivesync.sync.ConflictDecision
import com.anderson.onedrivesync.ui.components.EmptyState
import com.anderson.onedrivesync.ui.components.LoadingBox
import com.anderson.onedrivesync.ui.util.formatBytes
import com.anderson.onedrivesync.ui.util.relativeTime
import com.anderson.onedrivesync.ui.vm.PendingUiState
import com.anderson.onedrivesync.ui.vm.PendingViewModel

const val PENDING_TAB_CONFLICTS = 0
const val PENDING_TAB_DELETIONS = 1

/**
 * The two queues that need a user decision: conflicts (both sides changed) and deletions waiting
 * for confirmation. Both routes, `pending` and the notification-only `conflicts`, land on the
 * conflicts tab; the tab of the pending route can be changed by the user.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingScreen(
    initialTab: Int = PENDING_TAB_CONFLICTS,
    onCountsChanged: () -> Unit = {},
    viewModel: PendingViewModel = viewModel(factory = PendingViewModel.factory(LocalContext.current)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var pendingDecision by remember { mutableStateOf<Pair<Long, ConflictDecision>?>(null) }
    var confirmApproveAll by remember { mutableStateOf(false) }

    LaunchedEffect(state.errorMessage) {
        val message = state.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeError()
    }
    // A resolved conflict/deletion changes the shell badge; the counter also covers the batch case.
    LaunchedEffect(state.completedActions) {
        if (state.completedActions > 0) onCountsChanged()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_title_pending)) },
                actions = {
                    IconButton(onClick = viewModel::reload) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.pending_refresh),
                        )
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TabRow(selectedTabIndex = tab) {
                Tab(
                    selected = tab == PENDING_TAB_CONFLICTS,
                    onClick = { tab = PENDING_TAB_CONFLICTS },
                    text = { Text(stringResource(R.string.pending_tab_conflicts)) },
                )
                Tab(
                    selected = tab == PENDING_TAB_DELETIONS,
                    onClick = { tab = PENDING_TAB_DELETIONS },
                    text = { Text(stringResource(R.string.pending_tab_deletions)) },
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

                tab == PENDING_TAB_CONFLICTS -> ConflictsTab(
                    state = state,
                    onDecide = { id, decision -> pendingDecision = id to decision },
                )

                else -> DeletionsTab(
                    state = state,
                    onApprove = viewModel::approve,
                    onIgnore = viewModel::reject,
                    onApproveAll = { confirmApproveAll = true },
                )
            }
        }
    }

    val decision = pendingDecision
    if (decision != null) {
        DecisionDialog(
            decision = decision.second,
            onConfirm = {
                pendingDecision = null
                viewModel.resolve(decision.first, decision.second)
            },
            onDismiss = { pendingDecision = null },
        )
    }

    if (confirmApproveAll) {
        AlertDialog(
            onDismissRequest = { confirmApproveAll = false },
            title = { Text(stringResource(R.string.pending_approve_all_title)) },
            text = { Text(stringResource(R.string.pending_approve_all_message, state.deletions.size)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmApproveAll = false
                    viewModel.approveAll()
                }) {
                    Text(stringResource(R.string.pending_approve_all_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmApproveAll = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun ConflictsTab(
    state: PendingUiState,
    onDecide: (Long, ConflictDecision) -> Unit,
) {
    if (state.conflicts.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                icon = Icons.Outlined.CheckCircle,
                title = stringResource(R.string.pending_conflicts_empty_title),
                message = stringResource(R.string.pending_conflicts_empty_message),
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(state.conflicts, key = { row -> row.id }) { row ->
            ConflictCard(
                row = row,
                busy = row.id in state.busyConflicts,
                onDecide = { decision -> onDecide(row.id, decision) },
            )
        }
    }
}

@Composable
private fun ConflictCard(
    row: ConflictRow,
    busy: Boolean,
    onDecide: (ConflictDecision) -> Unit,
) {
    val now = System.currentTimeMillis()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                ) {
                    Text(
                        text = row.relPath.substringAfterLast('/'),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = row.relPath.substringBeforeLast('/', "").ifEmpty { "/" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            Text(
                text = stringResource(
                    R.string.pending_conflict_local,
                    formatBytes(row.localSize),
                    relativeTime(nowMs = now, thenMs = row.localMtimeMs.takeIf { it > 0 }),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    R.string.pending_conflict_remote,
                    formatBytes(row.remoteSize),
                    relativeTime(nowMs = now, thenMs = row.remoteMtimeMs.takeIf { it > 0 }),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onDecide(ConflictDecision.KEEP_LOCAL) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.pending_keep_local))
                }
                OutlinedButton(
                    onClick = { onDecide(ConflictDecision.KEEP_REMOTE) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.pending_keep_remote))
                }
            }
            Button(
                onClick = { onDecide(ConflictDecision.KEEP_BOTH) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.pending_keep_both))
            }
        }
    }
}

@Composable
private fun DeletionsTab(
    state: PendingUiState,
    onApprove: (Long) -> Unit,
    onIgnore: (Long) -> Unit,
    onApproveAll: () -> Unit,
) {
    if (state.deletions.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                icon = Icons.Outlined.CloudDone,
                title = stringResource(R.string.pending_deletions_empty_title),
                message = stringResource(R.string.pending_deletions_empty_message),
            )
        }
        return
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, end = 16.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onApproveAll, enabled = !state.approvingAll) {
                if (state.approvingAll) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(R.string.pending_approve_all))
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.deletions, key = { op -> op.id }) { op ->
                DeletionCard(
                    op = op,
                    busy = state.approvingAll || op.id in state.busyDeletions,
                    onApprove = { onApprove(op.id) },
                    onIgnore = { onIgnore(op.id) },
                )
            }
        }
    }
}

@Composable
private fun DeletionCard(
    op: PendingOp,
    busy: Boolean,
    onApprove: () -> Unit,
    onIgnore: () -> Unit,
) {
    val deletesLocal = op.kind == PendingKind.DELETE_LOCAL
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (deletesLocal) Icons.Outlined.Smartphone else Icons.Outlined.CloudOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                ) {
                    Text(
                        text = stringResource(
                            if (deletesLocal) R.string.pending_delete_local_label else R.string.pending_delete_remote_label,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = op.relPath,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val detail = op.detail
                    if (!detail.isNullOrBlank()) {
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onApprove, enabled = !busy) {
                    Text(stringResource(R.string.pending_approve))
                }
                TextButton(onClick = onIgnore, enabled = !busy) {
                    Text(stringResource(R.string.pending_ignore))
                }
            }
        }
    }
}

/** One sentence per decision: the dialog is the only place that explains what each button does. */
@Composable
private fun DecisionDialog(
    decision: ConflictDecision,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title = when (decision) {
        ConflictDecision.KEEP_LOCAL -> R.string.pending_keep_local_title
        ConflictDecision.KEEP_REMOTE -> R.string.pending_keep_remote_title
        ConflictDecision.KEEP_BOTH -> R.string.pending_keep_both_title
    }
    val message = when (decision) {
        ConflictDecision.KEEP_LOCAL -> R.string.pending_keep_local_message
        ConflictDecision.KEEP_REMOTE -> R.string.pending_keep_remote_message
        ConflictDecision.KEEP_BOTH -> R.string.pending_keep_both_message
    }
    val confirm = when (decision) {
        ConflictDecision.KEEP_LOCAL -> R.string.pending_keep_local
        ConflictDecision.KEEP_REMOTE -> R.string.pending_keep_remote
        ConflictDecision.KEEP_BOTH -> R.string.pending_keep_both
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
