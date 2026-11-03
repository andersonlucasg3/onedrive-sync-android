package com.anderson.onedrivesync.graph

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink

class GraphClient(
    private val tokenProvider: suspend (forceRefresh: Boolean) -> String,
    graphBaseUrl: String = DEFAULT_GRAPH_BASE,
    private val http: OkHttpClient = defaultHttpClient(),
    private val log: (String) -> Unit = {},
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val baseUrl: HttpUrl = graphBaseUrl.toHttpUrl()

    suspend fun me(): DriveUser = withContext(Dispatchers.IO) {
        send { token -> authorized(url("me"), token).get().build() }.use { it.decode() }
    }

    /** Drive metadata readable with `Files.ReadWrite.All` alone: the diagnostics call. */
    suspend fun drive(): DriveInfo = withContext(Dispatchers.IO) {
        val target = url("me", "drive").newBuilder()
            .addQueryParameter("\$select", "id,driveType,quota")
            .build()
        send { token -> authorized(target, token).get().build() }.use { it.decode() }
    }

    /** Drive root item, so the wizard can pair the whole drive with its real id. */
    suspend fun rootItem(): DriveItem = withContext(Dispatchers.IO) {
        send { token -> authorized(url("me", "drive", "root"), token).get().build() }.use { it.decode() }
    }

    suspend fun getItem(itemId: String): DriveItem = withContext(Dispatchers.IO) {
        send { token -> authorized(url("me", "drive", "items", itemId), token).get().build() }
            .use { it.decode() }
    }

    suspend fun children(itemId: String?): List<DriveItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<DriveItem>()
        var pageUrl = if (itemId == null) {
            url("me", "drive", "root", "children")
        } else {
            url("me", "drive", "items", itemId, "children")
        }
        var pages = 0
        while (pages < MAX_PAGES) {
            val page: DeltaPage = send { token -> authorized(pageUrl, token).get().build() }.use { it.decode() }
            items += page.value
            pages++
            pageUrl = page.nextLink?.toHttpUrl() ?: break
        }
        items
    }

    suspend fun deltaRoot(deltaLink: String? = null): DeltaPage {
        val target = deltaLink ?: url("me", "drive", "root", "delta").toString()
        return deltaFrom(target)
    }

    suspend fun deltaFrom(deltaUrl: String): DeltaPage = withContext(Dispatchers.IO) {
        send { token -> authorized(deltaUrl.toHttpUrl(), token).get().build() }.use { it.decode() }
    }

    suspend fun download(itemId: String, dest: File, onProgress: (Long, Long) -> Unit): Unit =
        withContext(Dispatchers.IO) {
            send { token -> authorized(url("me", "drive", "items", itemId, "content"), token).get().build() }
                .use { response ->
                    if (!response.isSuccessful) throw response.graphException()
                    val body = response.body
                    val total = body.contentLength().takeIf { it >= 0 } ?: 0L
                    var written = 0L
                    var reported = 0L
                    FileOutputStream(dest, true).use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(DOWNLOAD_CHUNK_BYTES)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                output.flush()
                                written += read
                                if (written - reported >= PROGRESS_STEP_BYTES) {
                                    onProgress(written, total)
                                    reported = written
                                }
                            }
                        }
                    }
                    onProgress(written, total)
                }
        }

    suspend fun uploadSmall(parentId: String, name: String, file: File): DriveItem = withContext(Dispatchers.IO) {
        val body = file.asRequestBody(OCTET_STREAM)
        send {
            authorized(url("me", "drive", "items", "$parentId:", "$name:", "content"), it).put(body).build()
        }.use { it.decode() }
    }

    suspend fun uploadLarge(
        parentId: String,
        name: String,
        file: File,
        onProgress: (Long, Long) -> Unit,
    ): DriveItem = withContext(Dispatchers.IO) {
        val sessionBody = buildJsonObject {
            put("item", buildJsonObject { put(CONFLICT_BEHAVIOR, "replace") })
        }.toString()
        val session: UploadSession = send {
            authorized(url("me", "drive", "items", "$parentId:", "$name:", "createUploadSession"), it)
                .post(sessionBody.toRequestBody(JSON_MEDIA))
                .build()
        }.use { it.decode() }

        val uploadUrl = session.uploadUrl.toHttpUrl()
        val total = file.length()
        var offset = 0L
        var item: DriveItem? = null
        while (offset < total) {
            currentCoroutineContext().ensureActive()
            val chunkSize = minOf(UPLOAD_CHUNK_BYTES, total - offset)
            val request = Request.Builder()
                .url(uploadUrl)
                .header("Content-Range", "bytes $offset-${offset + chunkSize - 1}/$total")
                .put(chunkBody(file, offset, chunkSize))
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw response.graphException()
                if (response.code == 200 || response.code == 201) {
                    item = graphJson.decodeFromString<DriveItem>(response.body.string())
                }
            }
            offset += chunkSize
            onProgress(offset, total)
        }
        item ?: throw GraphException(status = 0, code = null, message = "Upload session returned no item")
    }

    suspend fun createFolder(parentId: String, name: String): DriveItem = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("name", name)
            put("folder", buildJsonObject {})
            put(CONFLICT_BEHAVIOR, "fail")
        }.toString()
        send { authorized(url("me", "drive", "items", parentId, "children"), it).post(body.toRequestBody(JSON_MEDIA)).build() }
            .use { it.decode() }
    }

    suspend fun move(itemId: String, newParentId: String, newName: String): DriveItem = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("parentReference", buildJsonObject { put("id", newParentId) })
            put("name", newName)
        }.toString()
        send { authorized(url("me", "drive", "items", itemId), it).patch(body.toRequestBody(JSON_MEDIA)).build() }
            .use { it.decode() }
    }

    suspend fun delete(itemId: String): Unit = withContext(Dispatchers.IO) {
        send { token -> authorized(url("me", "drive", "items", itemId), token).delete().build() }.use { response ->
            if (!response.isSuccessful) throw response.graphException()
        }
    }

    suspend fun setMtime(itemId: String, epochMs: Long): DriveItem = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("fileSystemInfo", buildJsonObject { put("lastModifiedDateTime", Instant.ofEpochMilli(epochMs).toString()) })
        }.toString()
        send { authorized(url("me", "drive", "items", itemId), it).patch(body.toRequestBody(JSON_MEDIA)).build() }
            .use { it.decode() }
    }

    private suspend fun send(create: (String) -> Request): Response {
        var forceRefresh = false
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val response = http.newCall(create(tokenProvider(forceRefresh))).execute()
            if (response.code == 401 && !forceRefresh) {
                log("HTTP 401: renovando token")
                response.close()
                forceRefresh = true
                continue
            }
            if (response.code == 429 || response.code == 503 || response.code == 504) {
                if (attempt >= MAX_ATTEMPTS - 1) throw response.graphException()
                val status = response.code
                val retryAfterSeconds = response.header("Retry-After")?.trim()?.toLongOrNull()
                val errorCode = response.retryErrorCode()
                response.close()
                val requestedMs = retryAfterSeconds?.times(1000) ?: RETRY_BACKOFF_MS[attempt]
                val waitMs = minOf(requestedMs, MAX_RETRY_DELAY_MS)
                val origin = when {
                    retryAfterSeconds == null -> "backoff"
                    requestedMs > MAX_RETRY_DELAY_MS -> "Retry-After de ${retryAfterSeconds}s limitado a 120s"
                    else -> "Retry-After de ${retryAfterSeconds}s"
                }
                val codeSuffix = errorCode?.let { " ($it)" } ?: ""
                log(
                    "HTTP $status$codeSuffix: aguardando ${waitMs}ms antes de tentar de novo " +
                        "(tentativa ${attempt + 1}/$MAX_ATTEMPTS, $origin)",
                )
                sleep(waitMs)
                attempt++
                continue
            }
            return response
        }
    }

    private fun url(vararg segments: String): HttpUrl =
        baseUrl.newBuilder().apply { segments.forEach { addPathSegment(it) } }.build()

    private fun authorized(url: HttpUrl, token: String): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer $token")

    /** Graph error code of a retryable response, read before the body is discarded. */
    private fun Response.retryErrorCode(): String? {
        val text = runCatching { body.string() }.getOrDefault("")
        return runCatching {
            graphJson.parseToJsonElement(text).jsonObject["error"]?.jsonObject
                ?.get("code")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }

    private fun Response.graphException(): GraphException {
        val status = code
        val text = runCatching { body.string() }.getOrDefault("")
        val error = runCatching { graphJson.parseToJsonElement(text).jsonObject["error"]?.jsonObject }.getOrNull()
        val errorCode = error?.get("code")?.jsonPrimitive?.contentOrNull
        val message = error?.get("message")?.jsonPrimitive?.contentOrNull ?: "HTTP $status"
        val locationHeader = header("Location")
        close()
        return GraphException(status = status, code = errorCode, message = message, location = locationHeader)
    }

    private inline fun <reified T> Response.decode(): T {
        if (!isSuccessful) throw graphException()
        return graphJson.decodeFromString(body.string())
    }

    private fun chunkBody(file: File, offset: Long, size: Long): RequestBody = object : RequestBody() {
        override fun contentType(): MediaType = OCTET_STREAM

        override fun contentLength(): Long = size

        override fun writeTo(sink: BufferedSink) {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val buffer = ByteArray(UPLOAD_READ_BUFFER_BYTES)
                var remaining = size
                while (remaining > 0) {
                    val read = raf.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read == -1) break
                    sink.write(buffer, 0, read)
                    remaining -= read
                }
            }
        }
    }

    private companion object {
        const val CONFLICT_BEHAVIOR = "@microsoft.graph.conflictBehavior"
        const val MAX_PAGES = 50
        const val MAX_ATTEMPTS = 4
        const val MAX_RETRY_DELAY_MS = 120_000L
        const val DOWNLOAD_CHUNK_BYTES = 256 * 1024
        const val PROGRESS_STEP_BYTES = 1024 * 1024
        const val UPLOAD_CHUNK_BYTES = 10L * 1024 * 1024
        const val UPLOAD_READ_BUFFER_BYTES = 64 * 1024
        val RETRY_BACKOFF_MS = longArrayOf(2_000, 4_000, 8_000)
        val JSON_MEDIA: MediaType = "application/json".toMediaType()
        val OCTET_STREAM: MediaType = "application/octet-stream".toMediaType()
    }
}

private const val DEFAULT_GRAPH_BASE = "https://graph.microsoft.com/v1.0"

private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .build()
