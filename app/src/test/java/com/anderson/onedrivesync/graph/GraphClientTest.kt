package com.anderson.onedrivesync.graph

import com.anderson.onedrivesync.auth.AuthManager
import com.anderson.onedrivesync.auth.FakePkceStorage
import com.anderson.onedrivesync.auth.FakeTokenStore
import com.anderson.onedrivesync.auth.Tokens
import java.io.File
import java.time.Instant
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

class GraphClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: GraphClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = GraphClient(
            tokenProvider = { "token-1" },
            graphBaseUrl = server.url("/v1.0").toString(),
            http = OkHttpClient(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `drive requests selected fields and parses quota`() = runBlocking {
        server.enqueue(
            json(
                """
                {"id":"drive-1","driveType":"personal",
                 "quota":{"total":1000000000000,"used":250000000000,"remaining":750000000000}}
                """,
            ),
        )

        val drive = client.drive()

        assertEquals("drive-1", drive.id)
        assertEquals("personal", drive.driveType)
        assertEquals(750000000000L, drive.quota?.remaining)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1.0/me/drive", request.requestUrl?.encodedPath)
        assertEquals("id,driveType,quota", request.requestUrl?.queryParameter("\$select"))
        assertEquals("Bearer token-1", request.headers["Authorization"])
    }

    @Test
    fun `root item requests drive root and parses the dto`() = runBlocking {
        server.enqueue(
            json(
                """
                {"id":"ROOT!1","name":"root","folder":{"childCount":3},
                 "parentReference":{"path":"/drive/root"}}
                """,
            ),
        )

        val item = client.rootItem()

        assertEquals("ROOT!1", item.id)
        assertEquals("root", item.name)
        assertEquals(3L, item.folder?.childCount)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1.0/me/drive/root", request.path)
        assertEquals("Bearer token-1", request.headers["Authorization"])
    }

    @Test
    fun `children page fetches the first page without a skip token`() = runBlocking {
        server.enqueue(json("""{"value":[{"id":"F!1","name":"Docs","folder":{}}]}"""))

        val page = client.childrenPage("PARENT!1")

        assertEquals(listOf("F!1"), page.items.map { it.id })
        assertNull(page.nextLink)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals(
            listOf("v1.0", "me", "drive", "items", "PARENT!1", "children"),
            request.requestUrl?.pathSegments,
        )
        assertNull(request.requestUrl?.encodedQuery)
        assertNull(request.requestUrl?.queryParameter("\$skiptoken"))
    }

    @Test
    fun `children page of the root uses the root children url`() = runBlocking {
        server.enqueue(json("""{"value":[]}"""))

        val page = client.childrenPage(null)

        assertTrue(page.items.isEmpty())
        assertNull(page.nextLink)
        assertEquals(
            listOf("v1.0", "me", "drive", "root", "children"),
            server.takeRequest().requestUrl?.pathSegments,
        )
    }

    @Test
    fun `children page replays the absolute next link unchanged`() = runBlocking {
        val nextLink = server.url("/v1.0/me/drive/items/PARENT!1/children")
            .newBuilder()
            .addQueryParameter("\$skiptoken", "page-2")
            .build()
            .toString()
        server.enqueue(json("""{"value":[{"id":"F!2","name":"Fotos","folder":{}}]}"""))

        val page = client.childrenPage("PARENT!1", nextLink)

        assertEquals(listOf("F!2"), page.items.map { it.id })
        assertNull(page.nextLink)
        assertEquals(nextLink, server.takeRequest().requestUrl.toString())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `children page parses the next link of a partial listing`() = runBlocking {
        val nextLink = server.url("/v1.0/me/drive/root/children")
            .newBuilder()
            .addQueryParameter("\$skiptoken", "page-2")
            .build()
            .toString()
        server.enqueue(
            json("""{"value":[{"id":"F!1","name":"Docs","folder":{}}],"@odata.nextLink":"$nextLink"}"""),
        )

        val page = client.childrenPage(null)

        assertEquals(nextLink, page.nextLink)
        assertEquals(
            listOf("v1.0", "me", "drive", "root", "children"),
            server.takeRequest().requestUrl?.pathSegments,
        )
    }

    @Test
    fun `delta root requests drive root delta`() = runBlocking {
        server.enqueue(json("""{"value":[]}"""))

        val page = client.deltaRoot()

        assertTrue(page.value.isEmpty())
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1.0/me/drive/root/delta", request.path)
        assertEquals("Bearer token-1", request.headers["Authorization"])
    }

    @Test
    fun `delta root uses absolute delta link unchanged`() = runBlocking {
        val deltaLink = server.url("/v1.0/me/drive/root/delta?token=a%2Fb&${'$'}skiptoken=1").toString()
        server.enqueue(json("""{"value":[]}"""))

        client.deltaRoot(deltaLink)

        val request = server.takeRequest()
        assertEquals(deltaLink, request.requestUrl.toString())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `delta item requests the folder delta and replays the absolute link`() = runBlocking {
        val deltaLink = server.url("/v1.0/me/drive/items/DOCS!1/delta?token=a%2Fb").toString()
        server.enqueue(json("""{"value":[]}"""))
        server.enqueue(json("""{"value":[]}"""))

        client.deltaItem("DOCS!1")
        client.deltaItem("DOCS!1", deltaLink)

        val first = server.takeRequest()
        assertEquals("GET", first.method)
        assertEquals(listOf("v1.0", "me", "drive", "items", "DOCS!1", "delta"), first.requestUrl?.pathSegments)
        assertEquals(deltaLink, server.takeRequest().requestUrl.toString())
    }

    @Test
    fun `delta parses deleted moved and parent id only items`() = runBlocking {
        val nextLink = server.url("/v1.0/me/drive/root/delta?token=page-2").toString()
        val deltaLink = server.url("/v1.0/me/drive/root/delta?token=final").toString()
        server.enqueue(
            json(
                """
                {"value":[
                  {"id":"A!1","name":"a.txt","size":10,
                   "parentReference":{"id":"PARENT-1"},
                   "file":{"hashes":{"quickXorHash":"hash-1"},"mimeType":"text/plain"},
                   "fileSystemInfo":{"lastModifiedDateTime":"2024-05-01T10:00:00Z","createdDateTime":"2024-04-01T10:00:00Z"}},
                  {"id":"B!2","name":"b.txt","deleted":{"state":"deleted"},
                   "fileSystemInfo":{"lastModifiedDateTime":"2024-05-01T10:00:00Z"}}
                ],
                "@odata.nextLink":"$nextLink"}
                """,
            ),
        )
        server.enqueue(
            json(
                """
                {"value":[
                  {"id":"A!1","name":"a.txt","parentReference":{"id":"PARENT-2","path":"/drive/root:/new"}}
                ],
                "@odata.deltaLink":"$deltaLink"}
                """,
            ),
        )

        val first = client.deltaRoot()
        val moved = first.value.first()

        assertEquals(2, first.value.size)
        assertEquals("A!1", moved.id)
        assertEquals("PARENT-1", moved.parentReference?.id)
        assertNull(moved.parentReference?.path)
        assertEquals("hash-1", moved.file?.hashes?.quickXorHash)
        assertEquals("text/plain", moved.file?.mimeType)
        assertEquals(Instant.parse("2024-05-01T10:00:00Z").toEpochMilli(), moved.fileSystemInfo?.lastModifiedEpochMs)
        assertEquals(10L, moved.size)
        assertEquals("deleted", first.value.last().deleted?.state)
        assertEquals(nextLink, first.nextLink)

        val second = client.deltaRoot(first.nextLink)

        assertEquals("PARENT-2", second.value.first().parentReference?.id)
        assertEquals("/drive/root:/new", second.value.first().parentReference?.path)
        assertEquals(deltaLink, second.deltaLink)
        assertEquals("/v1.0/me/drive/root/delta", server.takeRequest().path)
        assertEquals("/v1.0/me/drive/root/delta?token=page-2", server.takeRequest().path)
    }

    @Test
    fun `move patches item with id containing bang`() = runBlocking {
        server.enqueue(json("""{"id":"01AB!CD","name":"novo.txt"}"""))

        val item = client.move("01AB!CD", "PARENT!1", "novo.txt")

        assertEquals("01AB!CD", item.id)
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals(listOf("v1.0", "me", "drive", "items", "01AB!CD"), request.requestUrl?.pathSegments)
        val body = request.body.readUtf8()
        assertTrue(body.contains(""""parentReference":{"id":"PARENT!1"}"""))
        assertTrue(body.contains(""""name":"novo.txt""""))
    }

    @Test
    fun `download streams content to file`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("hello world"))
        val dest = File.createTempFile("odsync", ".part")
        dest.deleteOnExit()
        val progress = mutableListOf<Pair<Long, Long>>()

        client.download("A!1", dest) { done, total -> progress += done to total }

        assertEquals("hello world", dest.readText())
        assertEquals(listOf(11L to 11L), progress)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals(listOf("v1.0", "me", "drive", "items", "A!1", "content"), request.requestUrl?.pathSegments)
    }

    @Test
    fun `retries 429 honoring retry after`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "0"))
        server.enqueue(json("""{"displayName":"Ana","userPrincipalName":"ana@example.com"}"""))

        val user = client.me()

        assertEquals("Ana", user.displayName)
        assertEquals("ana@example.com", user.userPrincipalName)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `429 logs the retry after wait and succeeds`() = runBlocking {
        val messages = mutableListOf<String>()
        val waits = mutableListOf<Long>()
        val logged = GraphClient(
            tokenProvider = { "token-1" },
            graphBaseUrl = server.url("/v1.0").toString(),
            http = OkHttpClient(),
            log = { messages += it },
            sleep = { waits += it },
        )
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "1"))
        server.enqueue(json("""{"displayName":"Ana"}"""))

        val user = logged.me()

        assertEquals("Ana", user.displayName)
        assertEquals(2, server.requestCount)
        assertEquals(listOf(1_000L), waits)
        assertEquals(1, messages.size)
        assertTrue(messages.single().contains("429"))
        assertTrue(messages.single().contains("Retry-After"))
    }

    @Test
    fun `retry after above the cap is truncated and logged`() = runBlocking {
        val messages = mutableListOf<String>()
        val waits = mutableListOf<Long>()
        val logged = GraphClient(
            tokenProvider = { "token-1" },
            graphBaseUrl = server.url("/v1.0").toString(),
            http = OkHttpClient(),
            log = { messages += it },
            sleep = { waits += it },
        )
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "9999"))
        server.enqueue(json("""{"displayName":"Ana"}"""))

        val user = logged.me()

        assertEquals("Ana", user.displayName)
        assertEquals(2, server.requestCount)
        assertEquals(listOf(120_000L), waits)
        assertEquals(1, messages.size)
        assertTrue(messages.single().contains("limitado a 120s"))
    }

    @Test
    fun `410 is not retried and exposes location`() {
        server.enqueue(
            MockResponse().setResponseCode(410)
                .setHeader("Location", "https://graph.microsoft.com/v1.0/me/drive/root/delta?token=restart"),
        )

        val error = assertThrows(GraphException::class.java) {
            runBlocking { client.deltaRoot() }
        }

        assertTrue(error.isGone())
        assertEquals("https://graph.microsoft.com/v1.0/me/drive/root/delta?token=restart", error.location)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `404 maps to not found`() {
        server.enqueue(json("""{"error":{"code":"itemNotFound","message":"The resource could not be found."}}""", 404))

        val error = assertThrows(GraphException::class.java) {
            runBlocking { client.getItem("MISSING!1") }
        }

        assertTrue(error.isNotFound())
        assertEquals(404, error.status)
        assertEquals("itemNotFound", error.code)
    }

    @Test
    fun `item by path uses path addressing and encodes spaces and accents`() = runBlocking {
        server.enqueue(json("""{"id":"F!9","name":"60 anos mamãe","folder":{}}"""))

        val item = client.itemByPath("PAI", "60 anos mamãe")

        assertEquals("F!9", item?.id)
        assertEquals("60 anos mamãe", item?.name)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1.0/me/drive/items/PAI:/60%20anos%20mam%C3%A3e", request.requestUrl?.encodedPath)
        assertEquals("Bearer token-1", request.headers["Authorization"])
    }

    @Test
    fun `item by path maps 404 to null instead of throwing`() = runBlocking {
        server.enqueue(
            json("""{"error":{"code":"itemNotFound","message":"The resource could not be found."}}""", 404),
        )

        val item = client.itemByPath("PAI", "ausente")

        assertNull(item)
        assertEquals("/v1.0/me/drive/items/PAI:/ausente", server.takeRequest().requestUrl?.encodedPath)
    }

    @Test
    fun `401 forces a single token refresh and retries`() = runBlocking {
        val forced = mutableListOf<Boolean>()
        val retrying = GraphClient(
            tokenProvider = { force ->
                forced += force
                if (force) "token-2" else "token-1"
            },
            graphBaseUrl = server.url("/v1.0").toString(),
            http = OkHttpClient(),
        )
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(json("""{"displayName":"Ana"}"""))

        val user = retrying.me()

        assertEquals("Ana", user.displayName)
        assertEquals(listOf(false, true), forced)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `expired token refreshes and new access token is used`() = runBlocking {
        val store = FakeTokenStore(Tokens("stale", "refresh-1", 0L))
        val auth = AuthManager(
            clientId = { "client-1" },
            tokens = store,
            pkce = FakePkceStorage(),
            http = OkHttpClient(),
            authBase = server.url("/").toString(),
        )
        val graph = GraphClient(
            tokenProvider = { force -> auth.accessToken(force) },
            graphBaseUrl = server.url("/v1.0").toString(),
            http = OkHttpClient(),
        )
        server.enqueue(json("""{"access_token":"fresh","refresh_token":"refresh-2","expires_in":3600}"""))
        server.enqueue(json("""{"displayName":"Ana"}"""))

        val user = graph.me()

        assertEquals("Ana", user.displayName)
        val tokenRequest = server.takeRequest()
        assertEquals("/consumers/oauth2/v2.0/token", tokenRequest.path)
        assertTrue(tokenRequest.body.readUtf8().contains("grant_type=refresh_token"))
        val meRequest = server.takeRequest()
        assertEquals("Bearer fresh", meRequest.headers["Authorization"])
        assertEquals("fresh", store.tokens?.accessToken)
        assertEquals("refresh-2", store.tokens?.refreshToken)
    }

    private fun json(body: String, status: Int = 200): MockResponse =
        MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
}
