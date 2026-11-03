package com.anderson.onedrivesync.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.anderson.onedrivesync.App
import com.anderson.onedrivesync.R
import com.anderson.onedrivesync.log.CrashLogger
import com.anderson.onedrivesync.log.shareLog
import com.anderson.onedrivesync.notify.Notifier
import com.anderson.onedrivesync.ui.nav.AppNavHost
import com.anderson.onedrivesync.ui.nav.initialDestination
import com.anderson.onedrivesync.ui.theme.OneDriveSyncTheme
import java.io.File

class MainActivity : ComponentActivity() {

    /** Last intent delivered by [onNewIntent]; navigation consumes it and clears the state. */
    private val newIntent = mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A corrupt database would otherwise crash the first screen that queries it. Touching it
        // here turns that failure into the error screen below instead of a crash loop.
        val startup = runCatching {
            (application as? App)?.container?.db?.readableDatabase
            // A notification tap can ask for a specific screen, but only once the app is usable.
            initialDestination(this, screenOf(intent))
        }
        setContent {
            OneDriveSyncTheme {
                val startDestination = startup.getOrNull()
                if (startDestination == null) {
                    StartupErrorScreen(
                        message = startup.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.startup_error_unknown),
                    )
                } else {
                    // Reading snapshot state here is what makes onNewIntent recompose the nav host.
                    AppNavHost(
                        startDestination = startDestination,
                        screenRequest = screenOf(newIntent.value),
                        onScreenRequestHandled = { newIntent.value = null },
                    )
                }
            }
        }
        // After the first frame: the manager checks in its own scope and reports through the
        // dialog in AppNavHost, so a slow network never delays the UI.
        (application as? App)?.container?.updateManager?.checkOnLaunch()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        newIntent.value = intent
    }
}

/** Notification extra; a missing or blank extra simply means "no specific screen". */
private fun screenOf(intent: Intent?): String? =
    intent?.getStringExtra(Notifier.EXTRA_SCREEN)?.takeIf { it.isNotBlank() }

/** Shown when even the container/database could not be used; the only job left is exporting logs. */
@Composable
private fun StartupErrorScreen(message: String) {
    val context = LocalContext.current
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.startup_error_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.startup_error_message, message),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = { shareStartupLogs(context, message) }) {
                Text(stringResource(R.string.startup_error_share))
            }
        }
    }
}

/**
 * Shares every file in `filesDir/crash` plus the startup failure through the same chooser the log
 * screen uses. The database is exactly what cannot be used here, so the probe is file-only.
 */
private fun shareStartupLogs(context: Context, error: String) {
    val report = buildString {
        appendLine("=== Falha na inicialização ===")
        appendLine(error)
        for (file in CrashLogger.pendingFiles(context)) {
            appendLine()
            appendLine("--- ${file.name} ---")
            appendLine(runCatching { file.readText() }.getOrDefault("(não foi possível ler o arquivo)"))
        }
    }
    val target = File(File(context.cacheDir, "logs").apply { mkdirs() }, "crash-${System.currentTimeMillis()}.txt")
    if (runCatching { target.writeText(report) }.isFailure) return
    runCatching { shareLog(context, target) }
}
