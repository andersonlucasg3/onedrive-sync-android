package com.anderson.onedrivesync.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anderson.onedrivesync.R
import com.anderson.onedrivesync.hyperos.HyperOsPermissions
import com.anderson.onedrivesync.ui.components.AuthSection
import com.anderson.onedrivesync.ui.components.ChecklistItem
import com.anderson.onedrivesync.ui.vm.OnboardingViewModel

/**
 * Single scrollable page: permissions (with the real state, re-read on every resume), the Microsoft
 * account and the "Continuar" gate. `allFiles` is the hard requirement: without it nothing syncs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onContinue: () -> Unit,
    viewModel: OnboardingViewModel = viewModel(factory = OnboardingViewModel.factory(LocalContext.current)),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    // The permission screens are separate activities: the only way to see the new status is to
    // re-check when this screen comes back to the foreground.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refresh() }

    val requestNotifications: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            HyperOsPermissions.openNotificationSettings(context)
        }
    }
    val notificationsLabel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        !state.checklist.notifications
    ) {
        stringResource(R.string.common_allow)
    } else {
        stringResource(R.string.common_open)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.onboarding_title)) },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
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
            Text(
                text = stringResource(R.string.onboarding_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionTitle(text = stringResource(R.string.onboarding_section_permissions))

            ChecklistItem(
                title = stringResource(R.string.perm_all_files_title),
                description = stringResource(R.string.perm_all_files_desc),
                done = state.checklist.allFiles,
                actionLabel = stringResource(R.string.common_open),
                onAction = { HyperOsPermissions.openAllFilesAccess(context) },
            )
            ChecklistItem(
                title = stringResource(R.string.perm_notifications_title),
                description = stringResource(R.string.perm_notifications_desc),
                done = state.checklist.notifications,
                actionLabel = notificationsLabel,
                onAction = requestNotifications,
            )
            ChecklistItem(
                title = stringResource(R.string.perm_battery_title),
                description = stringResource(R.string.perm_battery_desc),
                done = state.checklist.battery,
                actionLabel = stringResource(R.string.common_open),
                onAction = { HyperOsPermissions.requestIgnoreBatteryOptimizations(context) },
            )
            ChecklistItem(
                title = stringResource(R.string.perm_autostart_title),
                description = stringResource(R.string.perm_autostart_desc),
                done = state.checklist.autostart,
                actionLabel = stringResource(R.string.common_open),
                onAction = { HyperOsPermissions.openAutostartSettings(context) },
            )

            SectionTitle(text = stringResource(R.string.onboarding_section_account))
            AuthSection()

            Button(
                onClick = onContinue,
                enabled = state.canContinue,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.common_continue))
            }
            if (!state.canContinue) {
                Text(
                    text = stringResource(R.string.onboarding_continue_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(top = 8.dp),
    )
}
