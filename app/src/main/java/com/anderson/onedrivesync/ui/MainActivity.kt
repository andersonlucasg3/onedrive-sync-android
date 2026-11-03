package com.anderson.onedrivesync.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.anderson.onedrivesync.notify.Notifier
import com.anderson.onedrivesync.ui.nav.AppNavHost
import com.anderson.onedrivesync.ui.nav.initialDestination
import com.anderson.onedrivesync.ui.theme.OneDriveSyncTheme

class MainActivity : ComponentActivity() {

    /** Last intent delivered by [onNewIntent]; navigation consumes it and clears the state. */
    private val newIntent = mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A notification tap can ask for a specific screen, but only once the app is usable.
        val startDestination = initialDestination(this, intent?.getStringExtra(Notifier.EXTRA_SCREEN))
        setContent {
            // Reading snapshot state here is what makes onNewIntent recompose the nav host.
            val screen = newIntent.value?.getStringExtra(Notifier.EXTRA_SCREEN)
            OneDriveSyncTheme {
                AppNavHost(
                    startDestination = startDestination,
                    screenRequest = screen,
                    onScreenRequestHandled = { newIntent.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        newIntent.value = intent
    }
}
