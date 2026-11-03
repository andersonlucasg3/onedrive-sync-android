package com.anderson.onedrivesync.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anderson.onedrivesync.BuildConfig
import com.anderson.onedrivesync.R
import com.anderson.onedrivesync.hyperos.HyperOsPermissions
import com.anderson.onedrivesync.sync.SyncState
import com.anderson.onedrivesync.ui.components.AuthSection
import com.anderson.onedrivesync.ui.components.ChecklistItem
import com.anderson.onedrivesync.ui.util.relativeTime
import com.anderson.onedrivesync.ui.vm.DiagnosticsUiState
import com.anderson.onedrivesync.ui.vm.SettingsUiState
import com.anderson.onedrivesync.ui.vm.SettingsViewModel
import com.anderson.onedrivesync.ui.vm.UpdateUiState

private val SYNC_INTERVALS = listOf(15, 30, 60, 120, 360, 1440)

/** Account, sync schedule, HyperOS permissions, manual actions and app info. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(LocalContext.current)),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    var confirmResync by remember { mutableStateOf(false) }

    // Permission screens are separate activities: the only way to see the new state is on resume.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshChecklist()
                viewModel.refreshDiagnostics()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.errorMessage) {
        val message = state.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeError()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_title_settings)) },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle(text = stringResource(R.string.settings_account_title))
            AuthSection()

            SectionTitle(text = stringResource(R.string.settings_sync_title))
            SyncCard(
                state = state,
                onInterval = viewModel::setInterval,
                onWifiOnly = viewModel::setWifiOnly,
            )

            SectionTitle(text = stringResource(R.string.settings_permissions_title))
            PermissionChecklist(checklist = state.checklist)

            SectionTitle(text = stringResource(R.string.settings_advanced_title))
            AdvancedCard(
                syncing = syncState !is SyncState.Idle,
                onSyncNow = viewModel::syncNow,
                onCancel = viewModel::cancelSync,
                onReschedule = viewModel::reschedule,
            )

            SectionTitle(text = stringResource(R.string.settings_updates_title))
            UpdatesCard(
                currentVersion = BuildConfig.VERSION_NAME,
                state = updateState,
                onCheck = viewModel::checkUpdates,
                onInstall = viewModel::downloadAndInstall,
            )

            SectionTitle(text = stringResource(R.string.settings_diagnostics_title))
            DiagnosticsCard(
                diagnostics = state.diagnostics,
                onTest = viewModel::testConnection,
                onShare = viewModel::shareLogs,
                onFullResync = { confirmResync = true },
            )

            SectionTitle(text = stringResource(R.string.settings_about_title))
            AboutCard(onOpenRepository = { openUrl(context, context.getString(R.string.settings_repo_url)) })
        }
    }

    if (confirmResync) {
        AlertDialog(
            onDismissRequest = { confirmResync = false },
            title = { Text(stringResource(R.string.settings_diag_resync_title)) },
            text = { Text(stringResource(R.string.settings_diag_resync_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmResync = false
                    viewModel.forceFullResync()
                }) {
                    Text(stringResource(R.string.settings_diag_resync_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmResync = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun SyncCard(
    state: SettingsUiState,
    onInterval: (Int) -> Unit,
    onWifiOnly: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.settings_interval),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                IntervalMenu(minutes = state.syncIntervalMinutes, onSelect = onInterval)
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_wifi_only),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.settings_wifi_only_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.wifiOnly, onCheckedChange = onWifiOnly)
            }
        }
    }
}

@Composable
private fun IntervalMenu(minutes: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(intervalLabel(minutes))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SYNC_INTERVALS.forEach { option ->
                DropdownMenuItem(
                    text = { Text(intervalLabel(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun intervalLabel(minutes: Int): String =
    if (minutes < 60) {
        stringResource(R.string.settings_interval_minutes, minutes)
    } else {
        stringResource(R.string.settings_interval_hours, minutes / 60)
    }

@Composable
private fun PermissionChecklist(checklist: HyperOsPermissions.Checklist) {
    val context = LocalContext.current
    ChecklistItem(
        title = stringResource(R.string.perm_all_files_title),
        description = stringResource(R.string.perm_all_files_desc),
        done = checklist.allFiles,
        actionLabel = stringResource(R.string.common_open),
        onAction = { HyperOsPermissions.openAllFilesAccess(context) },
    )
    ChecklistItem(
        title = stringResource(R.string.perm_notifications_title),
        description = stringResource(R.string.perm_notifications_desc),
        done = checklist.notifications,
        actionLabel = stringResource(R.string.common_open),
        onAction = { HyperOsPermissions.openNotificationSettings(context) },
    )
    ChecklistItem(
        title = stringResource(R.string.perm_battery_title),
        description = stringResource(R.string.perm_battery_desc),
        done = checklist.battery,
        actionLabel = stringResource(R.string.common_open),
        onAction = { HyperOsPermissions.requestIgnoreBatteryOptimizations(context) },
    )
    ChecklistItem(
        title = stringResource(R.string.perm_autostart_title),
        description = stringResource(R.string.perm_autostart_desc),
        done = checklist.autostart,
        actionLabel = stringResource(R.string.common_open),
        onAction = { HyperOsPermissions.openAutostartSettings(context) },
    )
}

@Composable
private fun AdvancedCard(
    syncing: Boolean,
    onSyncNow: () -> Unit,
    onCancel: () -> Unit,
    onReschedule: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onSyncNow, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_sync_now))
            }
            if (syncing) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_cancel_sync))
                }
            }
            OutlinedButton(onClick = onReschedule, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_reschedule))
            }
        }
    }
}

@Composable
private fun AboutCard(onOpenRepository: () -> Unit) {
    val repository = stringResource(R.string.settings_repo_url)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = repository,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .clickable(onClick = onOpenRepository)
                    .padding(vertical = 4.dp),
            )
        }
    }
}

/** GitHub Releases check: the download always starts here, never in the background worker. */
@Composable
private fun UpdatesCard(
    currentVersion: String,
    state: UpdateUiState,
    onCheck: () -> Unit,
    onInstall: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_updates_current, currentVersion),
                style = MaterialTheme.typography.bodyMedium,
            )
            when (state) {
                UpdateUiState.Idle -> CheckButton(onCheck)
                UpdateUiState.Checking -> Text(
                    text = stringResource(R.string.settings_updates_checking),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                UpdateUiState.UpToDate -> {
                    Text(
                        text = stringResource(R.string.settings_updates_up_to_date),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CheckButton(onCheck)
                }

                is UpdateUiState.Available -> {
                    Text(
                        text = stringResource(R.string.settings_updates_available, state.info.versionName),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (state.info.notes.isNotBlank()) {
                        Text(
                            text = state.info.notes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.settings_updates_download))
                    }
                }

                is UpdateUiState.Downloading -> {
                    Text(
                        text = stringResource(R.string.settings_updates_downloading, state.progress),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(
                        progress = { state.progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                UpdateUiState.Installing -> Text(
                    text = stringResource(R.string.settings_updates_installing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                is UpdateUiState.Error -> {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    CheckButton(onCheck)
                }
            }
        }
    }
}

@Composable
private fun CheckButton(onCheck: () -> Unit) {
    Button(onClick = onCheck, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.settings_updates_check))
    }
}

/** Local summary for support plus the two actions that cannot be done without the app. */
@Composable
private fun DiagnosticsCard(
    diagnostics: DiagnosticsUiState,
    onTest: () -> Unit,
    onShare: () -> Unit,
    onFullResync: () -> Unit,
) {
    val now = System.currentTimeMillis()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(
                    R.string.settings_diag_account,
                    stringResource(
                        if (diagnostics.signedIn) R.string.settings_diag_signed_in else R.string.settings_diag_signed_out,
                    ),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.settings_diag_pairs, diagnostics.pairs.size),
                style = MaterialTheme.typography.bodyMedium,
            )
            diagnostics.pairs.forEach { pair ->
                Text(
                    text = stringResource(
                        R.string.settings_diag_pair_last_sync,
                        pair.displayName,
                        relativeTime(now, pair.lastSyncAt),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(R.string.settings_diag_pending, diagnostics.pendingCount, diagnostics.conflictCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.settings_diag_log, diagnostics.logLines),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            diagnostics.connectionResult?.let { result ->
                Text(
                    text = result,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            diagnostics.connectionError?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedButton(onClick = onTest, enabled = !diagnostics.checking, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(
                        if (diagnostics.checking) R.string.settings_diag_testing else R.string.settings_diag_test,
                    ),
                )
            }
            OutlinedButton(onClick = onShare, enabled = !diagnostics.sharing, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_diag_share))
            }
            OutlinedButton(onClick = onFullResync, enabled = !diagnostics.resyncing, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_diag_full_resync))
            }
        }
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // No browser installed: the URL stays visible as text.
    }
}
