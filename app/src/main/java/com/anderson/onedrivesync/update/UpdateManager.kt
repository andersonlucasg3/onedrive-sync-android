package com.anderson.onedrivesync.update

import android.content.Context
import com.anderson.onedrivesync.core.Prefs
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Single state machine for the whole update flow: launch check, Settings card and dialog. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Available(val info: ReleaseInfo) : UpdateState
    data class Downloading(val percent: Int) : UpdateState // -1 = indeterminado
    data object Installing : UpdateState
    data object UpToDate : UpdateState
    data class Error(val message: String) : UpdateState
}

/** The floating dialog only exists for these states; Settings renders the rest inline. */
fun shouldShowUpdateDialog(state: UpdateState): Boolean = when (state) {
    is UpdateState.Available,
    is UpdateState.Downloading,
    is UpdateState.Installing,
    is UpdateState.Error -> true

    UpdateState.Idle,
    UpdateState.Checking,
    UpdateState.UpToDate -> false
}

/** The APK of another version can carry the same size, so the version name is the final gate. */
fun apkMatchesRelease(archiveVersionName: String?, releaseVersionName: String): Boolean =
    archiveVersionName != null && archiveVersionName == releaseVersionName

/** One file per version: no separator survives, so a version can never escape `updates/`. */
fun apkFileName(versionName: String): String =
    "update-${versionName.replace(Regex("[^A-Za-z0-9._-]"), "_")}.apk"

/**
 * Owns every transition of the update flow so Settings and the dialog over the whole app never
 * keep a second copy of the same state.
 *
 * The launch check is silent: it is throttled, honors [Prefs.skippedUpdateVersion] and a network
 * failure just goes back to [UpdateState.Idle]. Anything the user asks for explicitly reports the
 * real failure instead.
 */
class UpdateManager(
    private val checker: ReleaseSource,
    private val applier: ApkInstaller,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val readArchiveVersion: (Context, File) -> String? = { context, apk ->
        context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.versionName
    },
    private val log: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val checkRunning = AtomicBoolean(false)
    private val downloadRunning = AtomicBoolean(false)
    private var lastAutoCheckAt: Long? = null

    /** Release behind [UpdateState.Available]/[UpdateState.Error]: retrying uses it. */
    private var pending: ReleaseInfo? = null

    /** Entry point on every app start; never blocks the caller. */
    fun checkOnLaunch() {
        if (state.value.isBusy()) return
        // The installed version is the skipped one: no request at all.
        if (prefs.skippedUpdateVersion == checker.currentVersion) return
        val now = clock()
        val last = lastAutoCheckAt
        if (last != null && now - last < AUTO_CHECK_INTERVAL_MS) return
        if (!checkRunning.compareAndSet(false, true)) return
        lastAutoCheckAt = now
        scope.launch { runCheck(automatic = true) }
    }

    /** Explicit "Verificar atualização": no throttle and no skip, failures become [UpdateState.Error]. */
    fun checkNow() {
        val current = state.value
        if (current is UpdateState.Downloading || current is UpdateState.Installing) return
        if (!checkRunning.compareAndSet(false, true)) return
        scope.launch { runCheck(automatic = false) }
    }

    /**
     * Downloads (reusing the cached APK only when its digest matches the release) and opens the
     * installer. Runs in the manager scope so a rotation does not cancel a download already in
     * progress.
     */
    suspend fun downloadAndInstall(context: Context) {
        val info = pending ?: return
        if (!downloadRunning.compareAndSet(false, true)) return
        scope.async { applyUpdate(context, info) }.await()
    }

    /** Closes the dialog only; the release is still offered on the next check. */
    fun dismiss() {
        if (state.value is UpdateState.Available || state.value is UpdateState.Error) {
            _state.value = UpdateState.Idle
        }
    }

    fun skipCurrentVersion() {
        val info = (state.value as? UpdateState.Available)?.info ?: return
        prefs.skippedUpdateVersion = info.versionName
        pending = null
        _state.value = UpdateState.Idle
    }

    private suspend fun runCheck(automatic: Boolean) {
        _state.value = UpdateState.Checking
        try {
            val release = checker.latest()
            val skipped = if (automatic) prefs.skippedUpdateVersion else null
            _state.value = when {
                // A failed check has no detail to show; the UI falls back to its generic message.
                release == null -> if (automatic) UpdateState.Idle else UpdateState.Error("")
                release.versionName == skipped -> UpdateState.Idle
                !isNewerVersion(checker.currentVersion, release.versionName) -> UpdateState.UpToDate
                else -> {
                    pending = release
                    UpdateState.Available(release)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = if (automatic) UpdateState.Idle else UpdateState.Error(e.message.orEmpty())
        } finally {
            checkRunning.set(false)
        }
    }

    private suspend fun applyUpdate(context: Context, info: ReleaseInfo) {
        try {
            val dir = updatesDir(context)
            val apk = File(dir, apkFileName(info.versionName))
            if (!isCached(apk, info)) {
                download(info, apk)
            }
            val cachedArchive = readArchiveVersion(context, apk)
            if (!apkMatchesRelease(cachedArchive, info.versionName)) {
                // Same name but another build: never hand this file to the installer.
                apk.delete()
                log(
                    "APK em cache não corresponde a ${info.versionName} " +
                        "(era ${cachedArchive ?: "desconhecida"}): baixando de novo",
                )
                download(info, apk)
                if (!apkMatchesRelease(readArchiveVersion(context, apk), info.versionName)) {
                    apk.delete()
                    throw IOException("APK baixado não corresponde à versão ${info.versionName}")
                }
            }
            _state.value = UpdateState.Installing
            applier.install(context, apk)
            // The installer reads the APK through the content URI: only older versions go away.
            deleteOtherApks(dir, keep = apk)
            _state.value = UpdateState.Available(info)
        } catch (e: CancellationException) {
            _state.value = UpdateState.Idle
            throw e
        } catch (e: Exception) {
            _state.value = UpdateState.Error(e.message.orEmpty())
        } finally {
            downloadRunning.set(false)
        }
    }

    private suspend fun download(info: ReleaseInfo, apk: File) {
        _state.value = UpdateState.Downloading(INDETERMINATE)
        applier.download(info, apk) { done, total ->
            _state.value = UpdateState.Downloading(percentOf(done, total))
        }
    }

    /**
     * A cached file is only trusted with a digest: two releases can have the exact same size, so a
     * release without sha256 always downloads again.
     */
    private fun isCached(apk: File, info: ReleaseInfo): Boolean {
        if (info.apkSize <= 0 || !apk.isFile || apk.length() != info.apkSize) return false
        val expected = info.sha256 ?: return false
        return sha256Of(apk).equals(expected, ignoreCase = true)
    }

    private fun deleteOtherApks(dir: File, keep: File) {
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.name != keep.name && file.name.endsWith(".apk", ignoreCase = true)) {
                file.delete()
            }
        }
    }

    private fun percentOf(done: Long, total: Long): Int =
        if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else INDETERMINATE

    private fun updatesDir(context: Context): File = File(context.cacheDir, UPDATE_DIR)

    private fun UpdateState.isBusy(): Boolean = when (this) {
        is UpdateState.Checking,
        is UpdateState.Available,
        is UpdateState.Downloading,
        is UpdateState.Installing -> true

        else -> false
    }

    private companion object {
        const val AUTO_CHECK_INTERVAL_MS = 10 * 60 * 1000L
        const val UPDATE_DIR = "updates"
        const val INDETERMINATE = -1
    }
}
