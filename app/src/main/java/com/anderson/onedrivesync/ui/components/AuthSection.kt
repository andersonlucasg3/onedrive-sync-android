package com.anderson.onedrivesync.ui.components

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anderson.onedrivesync.App
import com.anderson.onedrivesync.R
import com.anderson.onedrivesync.auth.AuthCancelledException
import com.anderson.onedrivesync.auth.AuthRequiredException
import com.anderson.onedrivesync.auth.AuthState
import com.anderson.onedrivesync.auth.DeviceCodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Microsoft account block: device-code sign-in (the code, the link and a copy button) and the
 * signed-in state. Self-contained so onboarding, settings and the wizard share it.
 */
@Composable
fun AuthSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val auth = app.container.authManager
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    val authState by auth.state.collectAsStateWithLifecycle()
    // Tokens survive process death, so "signed in" also comes from the token store, not only from
    // the state machine that starts as SignedOut on every launch.
    val signedIn by produceState(initialValue = false, authState) {
        value = authState is AuthState.SignedIn || withContext(Dispatchers.IO) { auth.isSignedIn() }
    }

    var busy by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (signedIn) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.CloudDone,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.auth_connected),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.auth_connected_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { scope.launch { auth.signOut() } }) {
                        Text(stringResource(R.string.auth_sign_out))
                    }
                }
            }
        } else {
            val pending = authState as? AuthState.Pending
            val error = authState as? AuthState.Error
            if (pending != null) {
                PendingCodeCard(
                    deviceCode = pending.deviceCode,
                    onCancel = {
                        auth.cancelSignIn()
                        busy = false
                    },
                )
            } else {
                Button(
                    onClick = {
                        // Ends editing first, so a pending tenant edit is saved on focus loss.
                        focusManager.clearFocus()
                        busy = true
                        localError = null
                        scope.launch {
                            try {
                                val result = auth.signIn()
                                result.exceptionOrNull()?.let { failure ->
                                    if (failure !is AuthCancelledException) {
                                        localError = failure.message
                                    }
                                }
                            } catch (_: AuthRequiredException) {
                                localError = context.getString(R.string.auth_required)
                            } finally {
                                busy = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                ) {
                    Text(stringResource(R.string.auth_sign_in))
                }
                val message = error?.message ?: localError
                if (message != null) {
                    ErrorCard(message = message)
                }
            }
        }
    }
}

@Composable
private fun PendingCodeCard(deviceCode: DeviceCodeInfo, onCancel: () -> Unit) {
    val context = LocalContext.current
    val copyLabel = stringResource(R.string.auth_pending_title)
    var copied by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.auth_pending_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.auth_pending_instructions),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = deviceCode.userCode,
                fontFamily = FontFamily.Monospace,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )
            Text(
                text = deviceCode.verificationUri,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .clickable { openBrowser(context, deviceCode.verificationUri) }
                    .padding(vertical = 4.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(ClipData.newPlainText(copyLabel, deviceCode.userCode))
                    copied = true
                }) {
                    Text(stringResource(R.string.auth_copy_code))
                }
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.auth_cancel))
                }
            }
            if (copied) {
                Text(
                    text = stringResource(R.string.auth_copied),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(
                    text = stringResource(R.string.auth_error_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

private fun openBrowser(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // No browser installed: the URL is still shown as text for the user to type elsewhere.
    }
}
