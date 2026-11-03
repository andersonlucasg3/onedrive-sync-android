package com.anderson.onedrivesync.ui.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.anderson.onedrivesync.App
import com.anderson.onedrivesync.BuildConfig
import com.anderson.onedrivesync.R
import com.anderson.onedrivesync.auth.isRedirectResult
import com.anderson.onedrivesync.ui.theme.OneDriveSyncTheme

/**
 * In-app browser for the Microsoft sign-in (Authorization Code + PKCE).
 *
 * Microsoft does not recommend embedded WebViews and can answer with `disallowed_useragent`
 * (or `AADSTS50011` when the redirect URI is not registered). Nothing is disguised here: the block
 * page is detected and reported, and the device code flow is one tap away in the toolbar.
 */
class AuthWebViewActivity : ComponentActivity() {

    /** Replaced by [onNewIntent] when a second attempt arrives while this activity is on top. */
    private val authorizeUrl = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authorizeUrl.value = intent.getStringExtra(EXTRA_AUTHORIZE_URL).orEmpty()
        if (authorizeUrl.value.isBlank()) {
            finish()
            return
        }
        enableEdgeToEdge()
        setContent {
            OneDriveSyncTheme {
                AuthWebViewScreen(
                    authorizeUrl = authorizeUrl.value,
                    onRedirect = { uri ->
                        (application as? App)?.container?.authManager?.completeInteractiveSignInInBackground(uri)
                        setResult(Activity.RESULT_OK)
                        finish()
                    },
                    onCancel = { cancelSignIn(Activity.RESULT_CANCELED) },
                    onDeviceCode = { cancelSignIn(RESULT_DEVICE_CODE) },
                )
            }
        }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    cancelSignIn(Activity.RESULT_CANCELED)
                }
            },
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A second attempt replaces the URL (and the PKCE) without recreating the activity.
        val url = intent.getStringExtra(EXTRA_AUTHORIZE_URL).orEmpty()
        if (url.isNotBlank()) authorizeUrl.value = url
    }

    private fun cancelSignIn(result: Int) {
        (application as? App)?.container?.authManager?.cancelInteractiveSignIn()
        setResult(result)
        finish()
    }

    companion object {
        const val EXTRA_AUTHORIZE_URL = "com.anderson.onedrivesync.extra.AUTHORIZE_URL"

        /** The toolbar fallback: the caller starts the device code flow instead. */
        const val RESULT_DEVICE_CODE = 2

        fun intent(context: Context, authorizeUrl: String): Intent =
            Intent(context, AuthWebViewActivity::class.java).putExtra(EXTRA_AUTHORIZE_URL, authorizeUrl)
    }
}

/** Markers Microsoft puts in the URL/title of the page shown when it refuses an embedded browser. */
private val BLOCKED_MARKERS = listOf("disallowed_useragent", "aadsts50011")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthWebViewScreen(
    authorizeUrl: String,
    onRedirect: (Uri) -> Unit,
    onCancel: () -> Unit,
    onDeviceCode: () -> Unit,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val blockedMessage = stringResource(R.string.auth_webview_blocked)
    var blocked by remember { mutableStateOf(false) }

    LaunchedEffect(blocked) {
        if (blocked) snackbarHostState.showSnackbar(blockedMessage)
    }

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setSupportMultipleWindows(false)
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                private var delivered = false

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val target = request?.takeIf { it.isForMainFrame }?.url ?: return false
                    if (!isRedirectResult(target, BuildConfig.DEFAULT_CLIENT_ID)) return false
                    deliver(target, view)
                    return true
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    val target = url?.let { Uri.parse(it) } ?: return
                    if (isRedirectResult(target, BuildConfig.DEFAULT_CLIENT_ID)) deliver(target, view)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    // The block reason sits in the URL or the title; no DOM parsing, no injection.
                    val haystack = "${url.orEmpty()} ${view?.title.orEmpty()}".lowercase()
                    if (!blocked && BLOCKED_MARKERS.any { it in haystack }) blocked = true
                }

                private fun deliver(target: Uri, view: WebView?) {
                    if (delivered) return
                    delivered = true
                    view?.stopLoading()
                    onRedirect(target)
                }
            }
        }
    }

    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }

    LaunchedEffect(webView, authorizeUrl) {
        webView.loadUrl(authorizeUrl)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.auth_webview_close),
                        )
                    }
                },
                title = { Text(stringResource(R.string.auth_webview_title)) },
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDeviceCode) {
                    Text(
                        text = stringResource(R.string.auth_webview_fallback),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
