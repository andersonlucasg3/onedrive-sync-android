package com.anderson.onedrivesync.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anderson.onedrivesync.R
import com.anderson.onedrivesync.update.UpdateState
import com.anderson.onedrivesync.update.shouldShowUpdateDialog

private const val NOTES_LINES = 4

/**
 * The update dialog lives once, over the whole nav host, so it covers every screen. It renders
 * [state] and only forwards the three user intentions; the transition rules stay in UpdateManager.
 */
@Composable
fun UpdateDialog(
    state: UpdateState,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
    onSkip: () -> Unit,
) {
    // UI-only flags: after the install screen opens the manager returns to Available, and that
    // must not pop the dialog back over the installer. "Ocultar" hides the progress only.
    var installRequested by rememberSaveable { mutableStateOf(false) }
    var hidden by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state) {
        when (state) {
            is UpdateState.Checking, UpdateState.Idle, UpdateState.UpToDate -> {
                installRequested = false
                hidden = false
            }
            is UpdateState.Available, is UpdateState.Error -> hidden = false
            else -> Unit
        }
    }

    val visible = shouldShowUpdateDialog(state) &&
        !(state is UpdateState.Available && installRequested) &&
        !(hidden && (state is UpdateState.Downloading || state is UpdateState.Installing))
    if (!visible) return

    val hideOrDismiss = {
        if (state is UpdateState.Downloading) hidden = true else onDismiss()
    }

    AlertDialog(
        onDismissRequest = hideOrDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.update_dialog_title))
                if (state is UpdateState.Available) {
                    Text(
                        text = stringResource(R.string.update_dialog_version, state.info.versionName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = { UpdateDialogBody(state = state, onSkip = onSkip) },
        confirmButton = {
            when (state) {
                is UpdateState.Available -> Button(
                    onClick = {
                        installRequested = true
                        onDownload()
                    },
                ) {
                    Text(stringResource(R.string.settings_updates_download))
                }

                is UpdateState.Downloading -> TextButton(onClick = hideOrDismiss) {
                    Text(stringResource(R.string.update_dialog_hide))
                }

                is UpdateState.Error -> TextButton(
                    onClick = {
                        installRequested = true
                        onDownload()
                    },
                ) {
                    Text(stringResource(R.string.common_try_again))
                }

                // Idle/Checking/Installing/UpToDate never reach here.
                else -> Unit
            }
        },
        dismissButton = {
            when (state) {
                is UpdateState.Available -> TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.update_dialog_later))
                }

                is UpdateState.Error -> TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.update_dialog_close))
                }

                else -> Unit
            }
        },
    )
}

@Composable
private fun UpdateDialogBody(state: UpdateState, onSkip: () -> Unit) {
    when (state) {
        is UpdateState.Available -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.info.notes.isNotBlank()) {
                Text(
                    text = state.info.notes.trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = NOTES_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onSkip) {
                Text(
                    text = stringResource(R.string.update_dialog_skip),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        is UpdateState.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = if (state.percent >= 0) {
                    stringResource(R.string.settings_updates_downloading, state.percent)
                } else {
                    stringResource(R.string.settings_updates_downloading_start)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.percent >= 0) {
                LinearProgressIndicator(
                    progress = { state.percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        UpdateState.Installing -> Text(stringResource(R.string.settings_updates_installing))

        is UpdateState.Error -> Text(
            text = if (state.message.isBlank()) {
                stringResource(R.string.settings_updates_error)
            } else {
                state.message
            },
            color = MaterialTheme.colorScheme.error,
        )

        else -> Unit
    }
}
