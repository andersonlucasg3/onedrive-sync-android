package com.anderson.onedrivesync.update

import com.anderson.onedrivesync.BuildConfig
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

private val updateJson = Json { ignoreUnknownKeys = true }

/** One GitHub release, already reduced to what the app needs. */
data class ReleaseInfo(
    val versionName: String,
    val tag: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    val sha256: String?,
    val publishedAt: String,
)

/**
 * Reads the latest release from the public repository (no token, 60 requests/hour per IP).
 *
 * Every failure — 404 (no release yet), other HTTP errors, broken JSON, network down — resolves to
 * null: the caller only cares whether there is something to install.
 */
class UpdateChecker(
    private val http: OkHttpClient,
    private val repo: String = DEFAULT_REPO,
    private val apiBase: String = DEFAULT_API_BASE,
) {
    val currentVersion: String = BuildConfig.VERSION_NAME

    suspend fun latest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${apiBase.trimEnd('/')}/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "$USER_AGENT_PREFIX/${BuildConfig.VERSION_NAME}")
            .get()
            .build()
        try {
            http.newCall(request).execute().use { response ->
                if (response.code == 200) {
                    runCatching { updateJson.decodeFromString<ReleaseDto>(response.body.string()) }
                        .getOrNull()
                        ?.toReleaseInfo()
                } else {
                    null
                }
            }
        } catch (e: IOException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun ReleaseDto.toReleaseInfo(): ReleaseInfo? {
        // Several assets are possible; the largest .apk is the one meant for this device.
        val apk = assets
            .filter { it.name.endsWith(".apk", ignoreCase = true) && it.downloadUrl.isNotBlank() }
            .maxByOrNull { it.size }
            ?: return null
        return ReleaseInfo(
            versionName = tagName.removePrefix("v").removePrefix("V"),
            tag = tagName,
            notes = body.orEmpty(),
            apkUrl = apk.downloadUrl,
            apkSize = apk.size,
            sha256 = apk.digest?.takeIf { it.startsWith("sha256:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.lowercase()
                ?.takeIf { it.isNotEmpty() },
            publishedAt = publishedAt.orEmpty(),
        )
    }

    private companion object {
        const val DEFAULT_REPO = "andersonlucasg3/onedrive-sync-android"
        const val DEFAULT_API_BASE = "https://api.github.com"
        const val USER_AGENT_PREFIX = "OneDriveSync"
    }
}

@Serializable
private data class ReleaseDto(
    @SerialName("tag_name") val tagName: String = "",
    val body: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val assets: List<AssetDto> = emptyList(),
)

@Serializable
private data class AssetDto(
    val name: String = "",
    @SerialName("browser_download_url") val downloadUrl: String = "",
    val size: Long = 0,
    val digest: String? = null,
)

/**
 * Semver-ish comparison with pre-release support: `1.0.0-beta.1` < `1.0.0` < `1.1.0` < `2.0.0`.
 *
 * A leading `v` is tolerated and a missing component counts as zero (`2.0` == `2.0.0`), because
 * release tags are typed by hand. Anything unparseable on either side means "not newer", so a
 * broken tag can never trigger an update loop.
 */
fun isNewerVersion(current: String, candidate: String): Boolean {
    val currentParsed = parseVersion(current) ?: return false
    val candidateParsed = parseVersion(candidate) ?: return false
    return compareVersions(candidateParsed, currentParsed) > 0
}

private class SemVer(val numbers: List<Int>, val preRelease: List<String>)

private fun parseVersion(raw: String): SemVer? {
    val text = raw.trim().removePrefix("v").removePrefix("V").substringBefore('+').trim()
    if (text.isEmpty()) return null
    val dash = text.indexOf('-')
    val core = if (dash >= 0) text.substring(0, dash) else text
    val preRelease = if (dash >= 0) text.substring(dash + 1) else ""
    val numbers = core.split('.').map { it.toIntOrNull() ?: return null }
    if (numbers.isEmpty() || numbers.size > 3 || numbers.any { it < 0 }) return null
    if (preRelease.isEmpty()) return SemVer(numbers, emptyList())
    val identifiers = preRelease.split('.')
    if (identifiers.any { it.isEmpty() }) return null
    return SemVer(numbers, identifiers)
}

private fun compareVersions(a: SemVer, b: SemVer): Int {
    for (i in 0 until maxOf(a.numbers.size, b.numbers.size)) {
        val left = a.numbers.getOrElse(i) { 0 }
        val right = b.numbers.getOrElse(i) { 0 }
        if (left != right) return left.compareTo(right)
    }
    // A release is newer than any pre-release of the same core version.
    if (a.preRelease.isEmpty() && b.preRelease.isEmpty()) return 0
    if (a.preRelease.isEmpty()) return 1
    if (b.preRelease.isEmpty()) return -1
    for (i in 0 until maxOf(a.preRelease.size, b.preRelease.size)) {
        val left = a.preRelease.getOrNull(i) ?: return -1
        val right = b.preRelease.getOrNull(i) ?: return 1
        val leftNumber = left.toIntOrNull()
        val rightNumber = right.toIntOrNull()
        val result = when {
            leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
            // Numeric identifiers have lower precedence than alphanumeric ones.
            leftNumber != null -> -1
            rightNumber != null -> 1
            else -> left.compareTo(right)
        }
        if (result != 0) return result
    }
    return 0
}
