package com.anderson.onedrivesync.auth

import android.app.Application
import android.net.Uri
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Authorization URL building and redirect parsing. Uri comes from Robolectric, as elsewhere. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class InteractiveAuthTest {
    @Test
    fun `authorize url carries every oauth parameter`() {
        val url = authorizeUrl(CLIENT_ID, DEFAULT_REDIRECT_URI, VERIFIER, STATE).toHttpUrl()

        assertEquals("https", url.scheme)
        assertEquals("login.microsoftonline.com", url.host)
        assertEquals("/consumers/oauth2/v2.0/authorize", url.encodedPath)
        assertEquals(CLIENT_ID, url.queryParameter("client_id"))
        assertEquals("code", url.queryParameter("response_type"))
        assertEquals(DEFAULT_REDIRECT_URI, url.queryParameter("redirect_uri"))
        assertEquals("query", url.queryParameter("response_mode"))
        assertEquals("Files.ReadWrite.All offline_access", url.queryParameter("scope"))
        assertEquals(challenge(VERIFIER), url.queryParameter("code_challenge"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals(STATE, url.queryParameter("state"))
        assertEquals("select_account", url.queryParameter("prompt"))
    }

    @Test
    fun `authorize url encodes the scope and keeps the redirect uri intact`() {
        val encoded = authorizeUrl(CLIENT_ID, DEFAULT_REDIRECT_URI, VERIFIER, STATE)

        assertTrue(encoded.contains("scope=Files.ReadWrite.All%20offline_access"))
        assertEquals(DEFAULT_REDIRECT_URI, encoded.toHttpUrl().queryParameter("redirect_uri"))
    }

    @Test
    fun `parse nativeclient redirect returns the code and the state`() {
        val result = parseRedirect(Uri.parse("$DEFAULT_REDIRECT_URI?code=code-1&state=$STATE"))

        assertEquals(RedirectResult.Success("code-1", STATE), result)
    }

    @Test
    fun `parse msal scheme redirect returns the provider error`() {
        val result = parseRedirect(
            Uri.parse("$MSAL_REDIRECT?error=access_denied&error_description=User%20declined&state=$STATE"),
        )

        assertEquals(RedirectResult.Failure("access_denied", "User declined", STATE), result)
    }

    @Test
    fun `parse redirect without a state still returns the code`() {
        val result = parseRedirect(Uri.parse("$MSAL_REDIRECT?code=code-1"))

        assertEquals(RedirectResult.Success("code-1", null), result)
    }

    @Test
    fun `parse redirect tolerates the code in the fragment`() {
        val result = parseRedirect(Uri.parse("$MSAL_REDIRECT#code=code-1&state=$STATE"))

        assertEquals(RedirectResult.Success("code-1", STATE), result)
    }

    @Test
    fun `parse redirect without a code or error is a failure`() {
        val result = parseRedirect(Uri.parse("$DEFAULT_REDIRECT_URI?state=$STATE"))

        assertTrue(result is RedirectResult.Failure)
        assertEquals(STATE, (result as RedirectResult.Failure).state)
    }

    @Test
    fun `optional msal redirect uri is derived from the client id`() {
        assertEquals("msalclient-1://auth", redirectUri(CLIENT_ID))
    }

    @Test
    fun `both redirect uris are recognized`() {
        assertTrue(isRedirectUri(Uri.parse("$DEFAULT_REDIRECT_URI?code=1&state=$STATE"), CLIENT_ID))
        assertTrue(isRedirectUri(Uri.parse("$MSAL_REDIRECT?code=1&state=$STATE"), CLIENT_ID))
        assertFalse(isRedirectUri(Uri.parse("msalclient-1://other"), CLIENT_ID))
        assertFalse(isRedirectUri(Uri.parse("msalother://auth"), CLIENT_ID))
        assertFalse(isRedirectUri(Uri.parse("https://login.microsoftonline.com/common/oauth2/authorize"), CLIENT_ID))
    }

    @Test
    fun `only a redirect that carries a code or an error is a result`() {
        assertTrue(isRedirectResult(Uri.parse("$DEFAULT_REDIRECT_URI?code=1&state=$STATE"), CLIENT_ID))
        assertTrue(isRedirectResult(Uri.parse("$MSAL_REDIRECT?error=access_denied&state=$STATE"), CLIENT_ID))
        assertFalse(isRedirectResult(Uri.parse("$MSAL_REDIRECT"), CLIENT_ID))
        assertFalse(isRedirectResult(Uri.parse("https://login.microsoftonline.com/consumers"), CLIENT_ID))
    }

    private companion object {
        const val CLIENT_ID = "client-1"
        const val MSAL_REDIRECT = "msalclient-1://auth"
        const val VERIFIER = "verifier-1"
        const val STATE = "state-1"
    }
}
