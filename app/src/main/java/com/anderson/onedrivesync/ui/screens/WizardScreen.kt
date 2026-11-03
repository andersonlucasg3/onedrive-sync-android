package com.anderson.onedrivesync.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anderson.onedrivesync.R
import com.anderson.onedrivesync.ui.components.EmptyState
import com.anderson.onedrivesync.ui.components.LoadingBox
import com.anderson.onedrivesync.ui.vm.WIZARD_STEP_CONFIRM
import com.anderson.onedrivesync.ui.vm.WIZARD_STEP_LOCAL
import com.anderson.onedrivesync.ui.vm.WIZARD_STEP_REMOTE
import com.anderson.onedrivesync.ui.vm.WizardUiState
import com.anderson.onedrivesync.ui.vm.WizardViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WizardScreen(
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: WizardViewModel = viewModel(factory = WizardViewModel.factory(LocalContext.current)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    BackHandler(enabled = state.canGoBack) { viewModel.goBack() }

    LaunchedEffect(state.createdPairId) {
        if (state.createdPairId != null) onClose()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(wizardTitle(state.step)) },
                navigationIcon = {
                    IconButton(onClick = { if (state.canGoBack) viewModel.goBack() else onClose() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (state.step) {
                WIZARD_STEP_LOCAL -> LocalStep(state = state, viewModel = viewModel)
                WIZARD_STEP_CONFIRM -> ConfirmStep(state = state, viewModel = viewModel)
                else -> RemoteStep(state = state, viewModel = viewModel, onOpenSettings = onOpenSettings)
            }
        }
    }
}

@Composable
private fun wizardTitle(step: Int): String = when (step) {
    WIZARD_STEP_LOCAL -> stringResource(R.string.wizard_title_local)
    WIZARD_STEP_CONFIRM -> stringResource(R.string.wizard_title_confirm)
    else -> stringResource(R.string.wizard_title_remote)
}

@Composable
private fun RemoteStep(
    state: WizardUiState,
    viewModel: WizardViewModel,
    onOpenSettings: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Breadcrumb(
            text = state.remoteStack.joinToString(" / ") { it.name },
            upEnabled = state.canGoUpRemote,
            upDescription = stringResource(R.string.wizard_up),
            onUp = viewModel::upRemote,
        )
        Box(modifier = Modifier.weight(1f)) {
            val remoteError = state.remoteError
            when {
                state.remoteLoading || state.remoteResolving -> LoadingBox(modifier = Modifier.align(Alignment.Center))
                remoteError != null -> ErrorBox(
                    message = remoteError,
                    authRequired = state.remoteAuthRequired,
                    onRetry = viewModel::retryRemote,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier.align(Alignment.Center),
                )

                state.remoteEmpty -> EmptyState(
                    icon = Icons.Outlined.Folder,
                    title = stringResource(R.string.wizard_empty_title),
                    message = stringResource(R.string.wizard_remote_empty_message),
                    modifier = Modifier.align(Alignment.Center),
                )

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.remoteItems, key = { entry -> entry.id }) { entry ->
                        FolderRow(name = entry.name, onClick = { viewModel.enterRemote(entry) })
                    }
                }
            }
        }
        BottomAction {
            Button(
                onClick = viewModel::chooseRemote,
                enabled = state.canChooseRemote,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.wizard_use_remote))
            }
        }
    }
}

@Composable
private fun LocalStep(state: WizardUiState, viewModel: WizardViewModel) {
    Column(modifier = Modifier.fillMaxSize()) {
        Breadcrumb(
            text = state.localPath,
            upEnabled = state.canGoUpLocal,
            upDescription = stringResource(R.string.wizard_up),
            onUp = viewModel::upLocal,
        )
        Box(modifier = Modifier.weight(1f)) {
            val localError = state.localError
            when {
                state.localLoading -> LoadingBox(modifier = Modifier.align(Alignment.Center))
                localError != null -> ErrorBox(
                    message = localError,
                    authRequired = false,
                    onRetry = viewModel::retryLocal,
                    onOpenSettings = null,
                    modifier = Modifier.align(Alignment.Center),
                )

                state.localItems.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.Folder,
                    title = stringResource(R.string.wizard_empty_title),
                    message = stringResource(R.string.wizard_local_empty_message),
                    modifier = Modifier.align(Alignment.Center),
                )

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.localItems, key = { folder -> folder.path }) { folder ->
                        FolderRow(name = folder.name, onClick = { viewModel.enterLocal(folder) })
                    }
                }
            }
        }
        BottomAction {
            Button(
                onClick = viewModel::chooseLocal,
                enabled = state.canUseLocal,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.wizard_use_local))
            }
        }
    }
}

@Composable
private fun ConfirmStep(state: WizardUiState, viewModel: WizardViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedTextField(
            value = state.pairName,
            onValueChange = viewModel::setPairName,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.wizard_name_label)) },
            singleLine = true,
        )

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LabeledPath(label = stringResource(R.string.wizard_remote_label), path = state.remotePath)
                HorizontalDivider()
                LabeledPath(label = stringResource(R.string.wizard_local_label), path = state.localPath)
            }
        }

        if (state.hasLocalContent) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.wizard_merge_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        val createError = state.createError
        if (createError != null) {
            Text(
                text = createError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Button(
            onClick = viewModel::createPair,
            enabled = state.canCreate,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    if (state.creating) R.string.wizard_creating else R.string.wizard_create,
                ),
            )
        }
    }
}

@Composable
private fun Breadcrumb(
    text: String,
    upEnabled: Boolean,
    upDescription: String,
    onUp: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onUp, enabled = upEnabled) {
            Icon(imageVector = Icons.Filled.KeyboardArrowUp, contentDescription = upDescription)
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun FolderRow(name: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider()
}

@Composable
private fun ErrorBox(
    message: String,
    authRequired: Boolean,
    onRetry: () -> Unit,
    onOpenSettings: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Button(onClick = onRetry) {
            Text(stringResource(R.string.common_try_again))
        }
        if (authRequired && onOpenSettings != null) {
            TextButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.wizard_open_settings))
            }
        }
    }
}

@Composable
private fun LabeledPath(label: String, path: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = path, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun BottomAction(content: @Composable () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            content()
        }
    }
}
