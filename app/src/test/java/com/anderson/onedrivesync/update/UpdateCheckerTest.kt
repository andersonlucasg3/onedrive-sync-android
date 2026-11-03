package com.anderson.onedrivesync.update

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UpdateCheckerTest {
    private lateinit var server: MockWebServer
    private lateinit var checker: UpdateChecker

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        checker = UpdateChecker(OkHttpClient(), repo = REPO, apiBase = server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `parses a complete release and picks the largest apk`() = runBlocking {
        server.enqueue(
            json(
                """
                {
                  "tag_name": "v1.2.0",
                  "body": "Corrige o delta e melhora o log.",
                  "published_at": "2026-10-01T12:00:00Z",
                  "assets": [
                    {"name": "OneDriveSync-v1.2.0.apk",
                     "browser_download_url": "https://github.com/andersonlucasg3/onedrive-sync-android/releases/download/v1.2.0/OneDriveSync-v1.2.0.apk",
                     "size": 12345678,
                     "digest": "sha256:ABCDEF0123456789"},
                    {"name": "OneDriveSync-v1.2.0-universal.apk",
                     "browser_download_url": "https://example.test/universal.apk",
                     "size": 22345678,
                     "digest": "sha256:1111111111111111"},
                    {"name": "source.zip",
                     "browser_download_url": "https://example.test/source.zip",
                     "size": 99999999}
                  ]
                }
                """,
            ),
        )

        val info = checkNotNull(checker.latest())

        assertEquals("1.2.0", info.versionName)
        assertEquals("v1.2.0", info.tag)
        assertEquals("Corrige o delta e melhora o log.", info.notes)
        assertEquals("https://example.test/universal.apk", info.apkUrl)
        assertEquals(22_345_678L, info.apkSize)
        assertEquals("1111111111111111", info.sha256)
        assertEquals("2026-10-01T12:00:00Z", info.publishedAt)

        val request = server.takeRequest()
        assertEquals("/repos/$REPO/releases/latest", request.path)
        assertEquals("application/vnd.github+json", request.headers["Accept"])
        assertTrue(checkNotNull(request.headers["User-Agent"]).startsWith("OneDriveSync/"))
    }

    @Test
    fun `release without an apk asset returns null`() = runBlocking {
        server.enqueue(
            json(
                """
                {"tag_name":"v1.3.0","body":"","published_at":"2026-10-02T00:00:00Z",
                 "assets":[{"name":"source.zip","browser_download_url":"https://example.test/source.zip","size":10}]}
                """,
            ),
        )

        assertNull(checker.latest())
    }

    @Test
    fun `404 returns null`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Not Found"}"""))

        assertNull(checker.latest())
    }

    @Test
    fun `network failure returns null`() = runBlocking {
        val dead = MockWebServer()
        dead.start()
        val base = dead.url("/").toString()
        dead.shutdown()

        val offline = UpdateChecker(OkHttpClient(), repo = REPO, apiBase = base)

        assertNull(offline.latest())
    }

    @Test
    fun `broken json returns null`() = runBlocking {
        server.enqueue(json("""{"tag_name":"""))

        assertNull(checker.latest())
    }

    private fun json(body: String): MockResponse =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

    private companion object {
        const val REPO = "andersonlucasg3/onedrive-sync-android"
    }
}
