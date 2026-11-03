package com.anderson.onedrivesync.auth

import java.net.URLDecoder
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AuthManagerTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `device code polls pending then slow down then signs in`() = runBlocking {
        server.enqueue(json(200, DEVICE_CODE))
        server.enqueue(json(400, """{"error":"authorization_pending"}"""))
        server.enqueue(json(400, """{"error":"slow_down"}"""))
        server.enqueue(json(200, """{"access_token":"access-1","refresh_token":"refresh-1","expires_in":3600}"""))

        val store = FakeTokenStore()
        val delays = mutableListOf<Long>()
        var pendingSeen = false
        val manager = manager(store)
        manager.delayFn = { millis ->
            delays += millis
            if (manager.state.value is AuthState.Pending) pendingSeen = true
        }

        val result = manager.signIn()

        assertTrue(result.isSuccess)
        assertTrue(pendingSeen)
        assertEquals(listOf(5_000L, 5_000L, 10_000L), delays)
        assertEquals("access-1", store.tokens?.accessToken)
        assertEquals("refresh-1", store.tokens?.refreshToken)
        assertEquals(AuthState.SignedIn, manager.state.value)
    }

    @Test
    fun `declined authorization fails with error state`() = runBlocking {
        server.enqueue(json(200, DEVICE_CODE))
        server.enqueue(json(400, """{"error":"authorization_declined"}"""))

        val store = FakeTokenStore()
        val manager = manager(store)
        manager.delayFn = {}

        val result = manager.signIn()

        assertTrue(result.isFailure)
        assertTrue(manager.state.value is AuthState.Error)
        assertNull(store.tokens)
    }

    @Test
    fun `missing client id fails without network`() {
        val manager = manager(FakeTokenStore(), clientId = null)

        val result = runBlocking { manager.signIn() }

        assertTrue(result.isFailure)
        assertEquals(AuthState.Error("Client ID não configurado"), manager.state.value)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `invalid grant on refresh clears tokens and requires auth`() {
        server.enqueue(json(400, """{"error":"invalid_grant","error_description":"expired"}"""))
        val store = FakeTokenStore(Tokens("stale", "refresh-1", 0L))
        val manager = manager(store)

        assertThrows(AuthRequiredException::class.java) {
            runBlocking { manager.accessToken() }
        }

        assertEquals(AuthState.SignedOut, manager.state.value)
        assertNull(store.tokens)
    }

    @Test
    fun `cancel sign in stops polling`() = runBlocking {
        server.enqueue(json(200, DEVICE_CODE))
        server.enqueue(json(400, """{"error":"authorization_pending"}"""))

        val manager = manager(FakeTokenStore())
        manager.delayFn = { manager.cancelSignIn() }

        val result = manager.signIn()

        assertTrue(result.isFailure)
        assertEquals(AuthState.SignedOut, manager.state.value)
    }

    @Test
    fun `sign in uses the consumers authority`() = runBlocking {
        server.enqueue(json(200, DEVICE_CODE))
        server.enqueue(json(200, ACCESS_TOKEN))

        val manager = manager(FakeTokenStore())
        manager.delayFn = {}

        assertTrue(manager.signIn().isSuccess)

        assertEquals("/consumers/oauth2/v2.0/devicecode", server.takeRequest().path)
        assertEquals("/consumers/oauth2/v2.0/token", server.takeRequest().path)
    }

    @Test
    fun `begin interactive sign in persists the pkce and builds the authorize url`() {
        val pkce = FakePkceStorage()
        val manager = manager(FakeTokenStore(), pkce)

        val url = manager.beginInteractiveSignIn()
        assertNotNull(url)
        val query = checkNotNull(url).toHttpUrl()

        val pending = pkce.pending
        assertNotNull(pending)
        assertEquals(DEFAULT_REDIRECT_URI, query.queryParameter("redirect_uri"))
        assertEquals("code", query.queryParameter("response_type"))
        assertEquals(pending?.state, query.queryParameter("state"))
        assertEquals(pending?.verifier?.let { challenge(it) }, query.queryParameter("code_challenge"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `begin interactive sign in without a client id reports the error`() {
        val manager = manager(FakeTokenStore(), clientId = null)

        assertNull(manager.beginInteractiveSignIn())
        assertEquals(AuthState.Error("Client ID não configurado"), manager.state.value)
    }

    @Test
    fun `interactive sign in exchanges the code with the verifier and saves tokens`() = runBlocking {
        server.enqueue(json(200, ACCESS_TOKEN))
        val store = FakeTokenStore()
        val pkce = FakePkceStorage(PendingPkce("verifier-1", "state-1", 0L))
        val manager = manager(store, pkce)

        val result = manager.completeInteractiveSignIn(RedirectResult.Success("code-1", "state-1"))

        assertTrue(result.isSuccess)
        val request = server.takeRequest()
        assertEquals("/consumers/oauth2/v2.0/token", request.path)
        val form = formOf(request)
        assertEquals("authorization_code", form["grant_type"])
        assertEquals("code-1", form["code"])
        assertEquals("client-1", form["client_id"])
        assertEquals(DEFAULT_REDIRECT_URI, form["redirect_uri"])
        assertEquals("verifier-1", form["code_verifier"])
        assertEquals("access-1", store.tokens?.accessToken)
        assertEquals("refresh-1", store.tokens?.refreshToken)
        assertEquals(AuthState.SignedIn, manager.state.value)
        assertNull(pkce.pending)
    }

    @Test
    fun `interactive sign in with a mismatched state fails without a request`() = runBlocking {
        val pkce = FakePkceStorage(PendingPkce("verifier-1", "state-1", 0L))
        val manager = manager(FakeTokenStore(), pkce)

        val result = manager.completeInteractiveSignIn(RedirectResult.Success("code-1", "state-2"))

        assertTrue(result.isFailure)
        assertTrue(manager.state.value is AuthState.Error)
        assertEquals(0, server.requestCount)
        // A forged intent must not abort the flow that is still open in the WebView.
        assertEquals("state-1", pkce.pending?.state)
    }

    @Test
    fun `interactive sign in with an expired pkce fails without a request`() = runBlocking {
        val pkce = FakePkceStorage(PendingPkce("verifier-1", "state-1", 0L))
        val manager = manager(FakeTokenStore(), pkce)
        manager.clock = { 11 * 60 * 1000L }

        val result = manager.completeInteractiveSignIn(RedirectResult.Success("code-1", "state-1"))

        assertTrue(result.isFailure)
        assertTrue(manager.state.value is AuthState.Error)
        assertEquals(0, server.requestCount)
        assertNull(pkce.pending)
    }

    @Test
    fun `interactive sign in with a provider error reports it and clears the pkce`() = runBlocking {
        val pkce = FakePkceStorage(PendingPkce("verifier-1", "state-1", 0L))
        val manager = manager(FakeTokenStore(), pkce)

        val result = manager.completeInteractiveSignIn(
            RedirectResult.Failure("access_denied", "AADSTS50011: redirect URI mismatch", "state-1"),
        )

        assertTrue(result.isFailure)
        assertEquals(AuthState.Error("AADSTS50011: redirect URI mismatch"), manager.state.value)
        assertEquals(0, server.requestCount)
        assertNull(pkce.pending)
    }

    @Test
    fun `interactive sign in without a pending pkce fails without a request`() = runBlocking {
        val manager = manager(FakeTokenStore())

        val result = manager.completeInteractiveSignIn(RedirectResult.Success("code-1", "state-1"))

        assertTrue(result.isFailure)
        assertTrue(manager.state.value is AuthState.Error)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `cancel interactive sign in clears the pkce and signs out`() {
        val pkce = FakePkceStorage(PendingPkce("verifier-1", "state-1", 0L))
        val manager = manager(FakeTokenStore(), pkce)

        manager.cancelInteractiveSignIn()

        assertNull(pkce.pending)
        assertEquals(AuthState.SignedOut, manager.state.value)
    }

    private fun manager(
        store: TokenStorage,
        pkce: PkceStorage = FakePkceStorage(),
        clientId: String? = "client-1",
    ): AuthManager = AuthManager(
        clientId = { clientId },
        tokens = store,
        pkce = pkce,
        http = OkHttpClient(),
        authBase = server.url("/").toString(),
    ).also { manager ->
        // Fixed clock: a pending PKCE created at 0 is inside the 10 minute window.
        manager.clock = { 1_000L }
    }

    private fun formOf(request: RecordedRequest): Map<String, String> =
        request.body.readUtf8()
            .split("&")
            .filter { it.isNotBlank() }
            .associate { field ->
                URLDecoder.decode(field.substringBefore('='), "UTF-8") to
                    URLDecoder.decode(field.substringAfter('=', ""), "UTF-8")
            }

    private fun json(status: Int, body: String): MockResponse =
        MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)

    private companion object {
        const val DEVICE_CODE =
            """{"device_code":"device-1","user_code":"ABCD-EFGH","verification_uri":"https://microsoft.com/devicelogin","expires_in":900,"interval":5,"message":"Use o código ABCD-EFGH"}"""
        const val ACCESS_TOKEN =
            """{"access_token":"access-1","refresh_token":"refresh-1","expires_in":3600}"""
    }
}
