package com.anderson.onedrivesync.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anderson.onedrivesync.R
import com.anderson.onedrivesync.db.LogRow
import com.anderson.onedrivesync.ui.components.EmptyState
import com.anderson.onedrivesync.ui.components.LoadingBox
import com.anderson.onedrivesync.ui.vm.LogLevelFilter
import com.anderson.onedrivesync.ui.vm.LogUiState
import com.anderson.onedrivesync.ui.vm.LogViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

private val LOG_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

private val WARN_CONTAINER = Color(0xFFFFE0B2)
private val WARN_CONTENT = Color(0xFF5D4200)

/** Persisted log, newest first: level filter, text search and copy/clear for bug reports. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    viewModel: LogViewModel = viewModel(factory = LogViewModel.factory(LocalContext.current)),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }

    val clipboard = context.getSystemService(ClipboardManager::class.java)
    val copyLabel = stringResource(R.string.log_copy)
    val copiedMessage = stringResource(R.string.log_copied)
    val copyVisible: () -> Unit = {
        val text = state.visible.joinToString("\n") { row ->
            "${timeOfDay(row.ts)} ${row.level} ${row.tag}: ${row.msg}"
        }
        clipboard?.setPrimaryClip(ClipData.newPlainText(copyLabel, text))
        scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
    }

    LaunchedEffect(state.errorMessage) {
        val message = state.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeError()
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_title_log)) },
                actions = {
                    IconButton(onClick = viewModel::share, enabled = !state.sharing) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = stringResource(R.string.log_share),
                        )
                    }
                    TextButton(onClick = copyVisible, enabled = state.visible.isNotEmpty()) {
                        Text(stringResource(R.string.log_copy))
                    }
                    IconButton(onClick = { confirmClear = true }) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteSweep,
                            contentDescription = stringResource(R.string.log_clear),
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
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                label = { Text(stringResource(R.string.log_search)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
            )
            FilterRow(state = state, onFilter = viewModel::setFilter)
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
                        icon = Icons.Outlined.Info,
                        title = stringResource(R.string.log_empty_title),
                        message = stringResource(R.string.log_empty_message),
                    )
                }

                state.visible.isEmpty() -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        icon = Icons.Outlined.Search,
                        title = stringResource(R.string.log_no_results_title),
                        message = stringResource(R.string.log_no_results_message),
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    // LogRow has no id: the timestamp plus its position is unique for one snapshot.
                    itemsIndexed(state.visible, key = { index, row -> "${row.ts}:$index" }) { _, row ->
                        LogRowItem(row = row)
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.log_clear_title)) },
            text = { Text(stringResource(R.string.log_clear_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.clear()
                }) {
                    Text(stringResource(R.string.log_clear_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun FilterRow(state: LogUiState, onFilter: (LogLevelFilter) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LogLevelFilter.entries.forEach { filter ->
            FilterChip(
                selected = state.filter == filter,
                onClick = { onFilter(filter) },
                label = { Text(filterLabel(filter)) },
            )
        }
    }
}

@Composable
private fun filterLabel(filter: LogLevelFilter): String = when (filter) {
    LogLevelFilter.ALL -> stringResource(R.string.log_level_all)
    LogLevelFilter.INFO -> stringResource(R.string.log_level_info)
    LogLevelFilter.WARN -> stringResource(R.string.log_level_warn)
    LogLevelFilter.ERROR -> stringResource(R.string.log_level_error)
}

@Composable
private fun LogRowItem(row: LogRow) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = timeOfDay(row.ts),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(8.dp))
            LevelChip(level = row.level)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = row.tag,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        SelectionContainer {
            Text(
                text = row.msg,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/** INFO/DEBUG neutral, WARN amber, ERROR the theme error pair; unknown levels fall back to neutral. */
@Composable
private fun LevelChip(level: String) {
    val container: Color
    val content: Color
    when (level.uppercase(Locale.ROOT)) {
        "WARN" -> {
            container = WARN_CONTAINER
            content = WARN_CONTENT
        }

        "ERROR" -> {
            container = MaterialTheme.colorScheme.errorContainer
            content = MaterialTheme.colorScheme.onErrorContainer
        }

        else -> {
            container = MaterialTheme.colorScheme.surfaceVariant
            content = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(50)) {
        Text(
            text = level,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

private fun timeOfDay(ts: Long): String =
    LOG_TIME.format(Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()))
