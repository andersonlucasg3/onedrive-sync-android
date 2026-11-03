package com.anderson.onedrivesync.auth

import com.anderson.onedrivesync.core.DEFAULT_TENANT
import com.anderson.onedrivesync.core.normalizeTenant
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class AuthManager(
    private val clientId: () -> String?,
    private val tenant: () -> String = { DEFAULT_TENANT },
    private val tokens: TokenStorage,
    private val http: OkHttpClient = OkHttpClient(),
    private val authBase: String = DEFAULT_AUTH_BASE,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow<AuthState>(AuthState.SignedOut)
    private val baseUrl: HttpUrl = authBase.toHttpUrl()

    @Volatile
    private var cancelled = false

    internal var delayFn: suspend (Long) -> Unit = { delay(it) }
    internal var clock: () -> Long = { System.currentTimeMillis() }

    val state: StateFlow<AuthState> = _state.asStateFlow()

    fun isSignedIn(): Boolean {
        val current = tokens.load() ?: return false
        return current.refreshToken.isNotEmpty() || current.expiresAtMs > clock()
    }

    fun cancelSignIn() {
        cancelled = true
    }

    suspend fun signIn(): Result<Unit> = withContext(Dispatchers.IO) {
        cancelled = false
        val cid = clientId()?.trim().orEmpty()
        if (cid.isEmpty()) return@withContext fail("Client ID não configurado")
        try {
            val deviceCode = requestDeviceCode(cid)
            val info = DeviceCodeInfo(
                userCode = deviceCode.userCode,
                verificationUri = deviceCode.verificationUri,
                expiresAtMs = clock() + deviceCode.expiresIn * 1000,
                message = deviceCode.message,
            )
            _state.value = AuthState.Pending(info)
            pollForToken(cid, deviceCode, info)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(e.message.orEmpty())
        }
    }

    suspend fun accessToken(forceRefresh: Boolean = false): String = withContext(Dispatchers.IO) {
        val current = tokens.load()
        if (current == null) {
            _state.value = AuthState.SignedOut
            throw AuthRequiredException()
        }
        if (!forceRefresh && current.expiresAtMs - clock() > REFRESH_SKEW_MS) {
            return@withContext current.accessToken
        }
        if (current.refreshToken.isEmpty()) {
            tokens.clear()
            _state.value = AuthState.SignedOut
            throw AuthRequiredException()
        }
        refresh(current.refreshToken)
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        cancelled = true
        tokens.clear()
        _state.value = AuthState.SignedOut
    }

    private suspend fun requestDeviceCode(cid: String): DeviceCodeResponse {
        val form = FormBody.Builder()
            .add("client_id", cid)
            .add("scope", SCOPE)
            .build()
        val (status, text) = post(endpoint("devicecode"), form)
        if (status !in 200..299) throw AuthException("Device code request failed (HTTP $status): ${errorCode(text) ?: text}")
        return json.decodeFromString(text)
    }

    private suspend fun pollForToken(cid: String, deviceCode: DeviceCodeResponse, info: DeviceCodeInfo): Result<Unit> {
        var intervalSeconds = deviceCode.interval
        while (true) {
            if (cancelled) {
                _state.value = AuthState.SignedOut
                return Result.failure(AuthCancelledException())
            }
            if (clock() >= info.expiresAtMs) return fail("Código de dispositivo expirado")
            delayFn(intervalSeconds * 1000)
            if (cancelled) {
                _state.value = AuthState.SignedOut
                return Result.failure(AuthCancelledException())
            }
            val form = FormBody.Builder()
                .add("grant_type", DEVICE_CODE_GRANT)
                .add("device_code", deviceCode.deviceCode)
                .add("client_id", cid)
                .build()
            val (status, text) = post(endpoint("token"), form)
            if (status in 200..299) {
                val token = json.decodeFromString<TokenResponse>(text)
                val refreshToken = token.refreshToken ?: return fail("Refresh token ausente")
                tokens.save(Tokens(token.accessToken, refreshToken, clock() + token.expiresIn * 1000))
                _state.value = AuthState.SignedIn
                return Result.success(Unit)
            }
            when (errorCode(text)) {
                "authorization_pending" -> Unit
                "slow_down" -> intervalSeconds += SLOW_DOWN_SECONDS
                "expired_token" -> return fail("Código de dispositivo expirado")
                "authorization_declined" -> return fail("Autorização recusada")
                else -> return fail(descriptionOf(text) ?: "Falha ao obter token (HTTP $status)")
            }
        }
    }

    private suspend fun refresh(refreshToken: String): String {
        val cid = clientId()?.trim().orEmpty()
        if (cid.isEmpty()) throw AuthException("Client ID não configurado")
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", cid)
            .add("scope", SCOPE)
            .build()
        val (status, text) = post(endpoint("token"), form)
        if (status !in 200..299) {
            if (errorCode(text) == "invalid_grant") {
                tokens.clear()
                _state.value = AuthState.SignedOut
                throw AuthRequiredException()
            }
            throw AuthException("Token refresh failed (HTTP $status): ${errorCode(text) ?: descriptionOf(text).orEmpty()}")
        }
        val token = json.decodeFromString<TokenResponse>(text)
        val updated = Tokens(token.accessToken, token.refreshToken ?: refreshToken, clock() + token.expiresIn * 1000)
        tokens.save(updated)
        _state.value = AuthState.SignedIn
        return updated.accessToken
    }

    private suspend fun post(url: HttpUrl, form: FormBody): Pair<Int, String> {
        val request = Request.Builder().url(url).post(form).build()
        return http.newCall(request).execute().use { it.code to it.body.string() }
    }

    // The tenant is editable in Settings while the app runs, so it is resolved per request.
    private val authority: HttpUrl
        get() = baseUrl.newBuilder()
            .addPathSegments("${normalizeTenant(tenant())}/oauth2/v2.0")
            .build()

    private fun endpoint(path: String): HttpUrl =
        authority.newBuilder().addPathSegment(path).build()

    private fun fail(message: String): Result<Unit> {
        _state.value = AuthState.Error(message)
        return Result.failure(AuthException(message))
    }

    private fun errorCode(body: String): String? = errorField(body, "error")

    private fun descriptionOf(body: String): String? = errorField(body, "error_description")

    private fun errorField(body: String, field: String): String? =
        runCatching {
            json.parseToJsonElement(body).jsonObject[field]?.jsonPrimitive?.contentOrNull
        }.getOrNull()

    private companion object {
        const val DEFAULT_AUTH_BASE = "https://login.microsoftonline.com"
        const val SCOPE = "Files.ReadWrite.All offline_access"
        const val DEVICE_CODE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
        const val SLOW_DOWN_SECONDS = 5L
        const val REFRESH_SKEW_MS = 5 * 60 * 1000L
    }
}

@Serializable
private data class DeviceCodeResponse(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("expires_in") val expiresIn: Long,
    val interval: Long = 5,
    val message: String? = null,
)

@Serializable
private data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long,
)
