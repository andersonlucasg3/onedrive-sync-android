package com.anderson.onedrivesync.auth

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
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
        val manager = AuthManager(
            clientId = { null },
            tokens = FakeTokenStore(),
            http = OkHttpClient(),
            authBase = server.url("/").toString(),
        )

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
    fun `sign in uses the configured tenant for device code and token`() = runBlocking {
        server.enqueue(json(200, DEVICE_CODE))
        server.enqueue(json(200, ACCESS_TOKEN))

        val manager = manager(FakeTokenStore(), tenant = "meu-tenant")
        manager.delayFn = {}

        assertTrue(manager.signIn().isSuccess)

        assertEquals("/meu-tenant/oauth2/v2.0/devicecode", server.takeRequest().path)
        assertEquals("/meu-tenant/oauth2/v2.0/token", server.takeRequest().path)
    }

    @Test
    fun `blank tenant falls back to consumers`() = runBlocking {
        server.enqueue(json(200, DEVICE_CODE))
        server.enqueue(json(200, ACCESS_TOKEN))

        val manager = manager(FakeTokenStore(), tenant = "   ")
        manager.delayFn = {}

        assertTrue(manager.signIn().isSuccess)

        assertEquals("/consumers/oauth2/v2.0/devicecode", server.takeRequest().path)
        assertEquals("/consumers/oauth2/v2.0/token", server.takeRequest().path)
    }

    @Test
    fun `default tenant is consumers`() = runBlocking {
        server.enqueue(json(200, DEVICE_CODE))
        server.enqueue(json(200, ACCESS_TOKEN))

        val manager = AuthManager(
            clientId = { "client-1" },
            tokens = FakeTokenStore(),
            http = OkHttpClient(),
            authBase = server.url("/").toString(),
        )
        manager.delayFn = {}

        assertTrue(manager.signIn().isSuccess)

        assertEquals("/consumers/oauth2/v2.0/devicecode", server.takeRequest().path)
        assertEquals("/consumers/oauth2/v2.0/token", server.takeRequest().path)
    }

    @Test
    fun `refresh uses the configured tenant`() = runBlocking {
        server.enqueue(json(200, REFRESHED_TOKEN))
        val store = FakeTokenStore(Tokens("stale", "refresh-1", 0L))
        val manager = manager(store, tenant = "meu-tenant")

        assertEquals("access-2", manager.accessToken())

        val request = server.takeRequest()
        assertEquals("/meu-tenant/oauth2/v2.0/token", request.path)
        assertEquals("access-2", store.tokens?.accessToken)
    }

    private fun manager(store: TokenStorage, tenant: String = "consumers"): AuthManager = AuthManager(
        clientId = { "client-1" },
        tenant = { tenant },
        tokens = store,
        http = OkHttpClient(),
        authBase = server.url("/").toString(),
    )

    private fun json(status: Int, body: String): MockResponse =
        MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)

    private companion object {
        const val DEVICE_CODE =
            """{"device_code":"device-1","user_code":"ABCD-EFGH","verification_uri":"https://microsoft.com/devicelogin","expires_in":900,"interval":5,"message":"Use o código ABCD-EFGH"}"""
        const val ACCESS_TOKEN =
            """{"access_token":"access-1","refresh_token":"refresh-1","expires_in":3600}"""
        const val REFRESHED_TOKEN =
            """{"access_token":"access-2","refresh_token":"refresh-2","expires_in":3600}"""
    }
}
