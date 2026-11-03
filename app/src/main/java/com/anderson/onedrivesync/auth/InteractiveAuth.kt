package com.anderson.onedrivesync.auth

import android.net.Uri
import okhttp3.HttpUrl.Companion.toHttpUrl

/** What came back in the browser redirect: the authorization code or the provider error. */
sealed interface RedirectResult {
    val state: String?

    data class Success(val code: String, override val state: String?) : RedirectResult

    data class Failure(
        val error: String,
        val errorDescription: String?,
        override val state: String?,
    ) : RedirectResult
}

/**
 * Redirect URI that every "Mobile and desktop applications" registration already carries, so the
 * in-app WebView needs no portal change. The per-app scheme below is accepted too, for a
 * registration that adds it.
 */
const val DEFAULT_REDIRECT_URI = "https://login.microsoftonline.com/common/oauth2/nativeclient"

private const val AUTHORIZE_ENDPOINT = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize"
private const val NATIVE_CLIENT_HOST = "login.microsoftonline.com"
private const val NATIVE_CLIENT_PATH = "/common/oauth2/nativeclient"

internal const val INTERACTIVE_SCOPE = "Files.ReadWrite.All offline_access"

/** A public client on mobile may use `msal<clientId>` as a custom scheme. */
fun redirectScheme(clientId: String): String = "msal$clientId"

fun redirectUri(clientId: String): String = "${redirectScheme(clientId)}://auth"

/** Authorization Code + PKCE request; OkHttp's builder owns the URL encoding of every value. */
fun authorizeUrl(clientId: String, redirectUri: String, verifier: String, state: String): String =
    AUTHORIZE_ENDPOINT.toHttpUrl().newBuilder()
        .addQueryParameter("client_id", clientId)
        .addQueryParameter("response_type", "code")
        .addQueryParameter("redirect_uri", redirectUri)
        .addQueryParameter("response_mode", "query")
        .addQueryParameter("scope", INTERACTIVE_SCOPE)
        .addQueryParameter("code_challenge", challenge(verifier))
        .addQueryParameter("code_challenge_method", "S256")
        .addQueryParameter("state", state)
        .addQueryParameter("prompt", "select_account")
        .build()
        .toString()

/** True when [uri] is one of the two redirects this app accepts. */
fun isRedirectUri(uri: Uri, clientId: String): Boolean {
    val scheme = uri.scheme.orEmpty()
    if (scheme.equals(redirectScheme(clientId), ignoreCase = true) &&
        uri.host?.equals("auth", ignoreCase = true) == true
    ) {
        return true
    }
    return scheme.equals("https", ignoreCase = true) &&
        uri.host?.equals(NATIVE_CLIENT_HOST, ignoreCase = true) == true &&
        uri.encodedPath == NATIVE_CLIENT_PATH
}

/** True when [uri] is one of our redirects and already carries the code or the provider error. */
fun isRedirectResult(uri: Uri, clientId: String): Boolean {
    if (!isRedirectUri(uri, clientId)) return false
    val params = redirectParameters(uri)
    return params.containsKey("code") || params.containsKey("error")
}

/**
 * Reads `?code=...` (response_mode=query) and also tolerates `#code=...`, which is what a
 * misconfigured `response_mode=fragment` would produce. A redirect without a code is reported as a
 * failure instead of returning `null`.
 */
fun parseRedirect(uri: Uri): RedirectResult {
    val params = redirectParameters(uri)
    val state = params["state"]
    val error = params["error"]
    if (error != null) return RedirectResult.Failure(error, params["error_description"], state)
    val code = params["code"]
        ?: return RedirectResult.Failure(
            error = "invalid_redirect",
            errorDescription = "Código de autorização ausente no redirect",
            state = state,
        )
    return RedirectResult.Success(code, state)
}

private fun redirectParameters(uri: Uri): Map<String, String> {
    val fromQuery = parametersOf(uri.encodedQuery)
    if (fromQuery.containsKey("code") || fromQuery.containsKey("error")) return fromQuery
    val fromFragment = parametersOf(uri.encodedFragment)
    if (fromFragment.containsKey("code") || fromFragment.containsKey("error")) return fromFragment
    // No result yet (or a malformed redirect): keep the query so `state` still reaches the caller.
    return fromQuery
}

private fun parametersOf(encoded: String?): Map<String, String> {
    if (encoded.isNullOrBlank()) return emptyMap()
    // A malformed query can arrive from any app that targets this scheme: never throw on it.
    val url = runCatching { "https://redirect.invalid/?$encoded".toHttpUrl() }.getOrNull() ?: return emptyMap()
    return url.queryParameterNames.associateWith { name -> url.queryParameter(name).orEmpty() }
}
