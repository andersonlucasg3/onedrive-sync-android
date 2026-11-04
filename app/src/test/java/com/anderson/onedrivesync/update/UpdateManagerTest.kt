package com.anderson.onedrivesync.update

import android.app.Application
import android.content.Context
import com.anderson.onedrivesync.core.PREFS_FILE
import com.anderson.onedrivesync.core.Prefs
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

private const val CURRENT = "1.0.0"

private fun release(version: String = "1.2.0") = ReleaseInfo(
    versionName = version,
    tag = "v$version",
    notes = "Correções e melhorias.",
    apkUrl = "https://example.test/app.apk",
    apkSize = 8L,
    sha256 = null,
    publishedAt = "2026-10-01T12:00:00Z",
)

/**
 * The update state machine on the JVM: the release endpoint and the APK layer are fakes, so no
 * request leaves the machine and no installer is opened. Only [Prefs] and the cache directory come
 * from Robolectric; the APK version reader is a lambda because no real package can be parsed here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class UpdateManagerTest {
    private lateinit var context: Context
    private lateinit var prefs: Prefs
    private lateinit var checker: FakeReleaseSource
    private lateinit var applier: FakeApkInstaller
    private var now = 1_000_000L
    private lateinit var manager: UpdateManager
    private var archiveVersion: String? = "1.2.0"
    private var archiveVersionProvider: ((File) -> String?)? = null
    private val logged = mutableListOf<String>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).edit().clear().commit()
        prefs = Prefs(context)
        checker = FakeReleaseSource()
        applier = FakeApkInstaller()
        manager = UpdateManager(
            checker = checker,
            applier = applier,
            prefs = prefs,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            clock = { now },
            readArchiveVersion = { _, apk -> archiveVersionProvider?.invoke(apk) ?: archiveVersion },
            log = { logged += it },
        )
    }

    @Test
    fun `checkOnLaunch offers a newer release`() {
        manager.checkOnLaunch()

        assertEquals(1, checker.calls)
        val state = manager.state.value
        assertTrue(state is UpdateState.Available)
        assertEquals("1.2.0", (state as UpdateState.Available).info.versionName)
    }

    @Test
    fun `checkOnLaunch is throttled and keeps the available release`() {
        manager.checkOnLaunch()
        assertTrue(manager.state.value is UpdateState.Available)

        manager.checkOnLaunch()
        assertEquals(1, checker.calls)

        // Dismiss only closes the dialog; the 10-minute window still blocks another automatic check.
        manager.dismiss()
        manager.checkOnLaunch()
        assertEquals(1, checker.calls)
        assertEquals(UpdateState.Idle, manager.state.value)
    }

    @Test
    fun `skipped installed version never reaches the checker`() {
        prefs.skippedUpdateVersion = checker.currentVersion

        manager.checkOnLaunch()

        assertEquals(0, checker.calls)
        assertEquals(UpdateState.Idle, manager.state.value)
    }

    @Test
    fun `up to date release is reported without opening the dialog`() {
        checker.next = release(version = checker.currentVersion)

        manager.checkOnLaunch()

        assertEquals(UpdateState.UpToDate, manager.state.value)
        assertFalse(shouldShowUpdateDialog(manager.state.value))
        assertTrue(shouldShowUpdateDialog(UpdateState.Available(release())))
    }

    @Test
    fun `network failure is silent on launch and an error when asked`() {
        checker.next = null

        manager.checkOnLaunch()
        assertEquals(UpdateState.Idle, manager.state.value)

        manager.checkNow()
        assertTrue(manager.state.value is UpdateState.Error)
    }

    @Test
    fun `downloadAndInstall reports progress and opens the installer`() {
        manager.checkOnLaunch()
        val observed = mutableListOf<UpdateState>()
        // The fake records the manager state right after each callback, with no dispatcher involved.
        applier.observe = { observed += manager.state.value }

        runBlocking { manager.downloadAndInstall(context) }

        val percents = observed.filterIsInstance<UpdateState.Downloading>().map { it.percent }
        assertEquals(listOf(-1, 0, 50, 100), percents)
        assertTrue(observed.any { it is UpdateState.Installing })
        assertEquals(1, applier.installs)
        // The APK stays cached, so the manager goes back to offering the same release.
        assertTrue(manager.state.value is UpdateState.Available)
    }

    @Test
    fun `download failure becomes an error and stays retryable`() {
        manager.checkOnLaunch()
        applier.failure = IOException("sem espaço no cache")

        runBlocking { manager.downloadAndInstall(context) }

        val state = manager.state.value
        assertTrue(state is UpdateState.Error)
        assertEquals("sem espaço no cache", (state as UpdateState.Error).message)

        applier.failure = null
        runBlocking { manager.downloadAndInstall(context) }

        assertEquals(1, applier.installs)
        assertTrue(manager.state.value is UpdateState.Available)
    }

    @Test
    fun `skipCurrentVersion remembers the release and closes`() {
        manager.checkOnLaunch()

        manager.skipCurrentVersion()

        assertEquals("1.2.0", prefs.skippedUpdateVersion)
        assertEquals(UpdateState.Idle, manager.state.value)
    }

    @Test
    fun `checkNow ignores the throttle`() {
        manager.checkOnLaunch()
        assertEquals(1, checker.calls)

        manager.checkNow()

        assertEquals(2, checker.calls)
    }

    @Test
    fun `cached apk with the same size and a different sha256 is downloaded again`() {
        manager.checkOnLaunch()
        runBlocking { manager.downloadAndInstall(context) }
        assertEquals(1, applier.downloads)

        val apk = cacheFile("1.2.0")
        val trusted = sha256Of(apk)
        assertEquals(8L, apk.length())

        // Another build with the exact same size: only the digest can tell them apart.
        apk.writeBytes(ByteArray(8) { 1 })
        checker.next = release().copy(sha256 = trusted)
        manager.checkNow()

        runBlocking { manager.downloadAndInstall(context) }

        assertEquals(2, applier.downloads)
        assertEquals(2, applier.installs)
        assertEquals(trusted, sha256Of(apk))
    }

    @Test
    fun `cached apk with a different size is downloaded again`() {
        val apk = cacheFile("1.2.0").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(3))
        }

        manager.checkOnLaunch()
        runBlocking { manager.downloadAndInstall(context) }

        assertEquals(1, applier.downloads)
        assertEquals(1, applier.installs)
        assertEquals(8L, apk.length())
    }

    @Test
    fun `release without sha256 never reuses the cached apk`() {
        manager.checkOnLaunch()
        runBlocking { manager.downloadAndInstall(context) }
        assertEquals(1, applier.downloads)

        runBlocking { manager.downloadAndInstall(context) }

        assertEquals(2, applier.downloads)
        assertEquals(2, applier.installs)
    }

    @Test
    fun `apk whose version differs from the release is downloaded again`() {
        var reads = 0
        archiveVersionProvider = { _ -> if (++reads == 1) "1.1.0" else "1.2.0" }

        manager.checkOnLaunch()
        runBlocking { manager.downloadAndInstall(context) }

        assertEquals(2, applier.downloads)
        assertEquals(1, applier.installs)
        assertEquals(
            listOf("APK em cache não corresponde a 1.2.0 (era 1.1.0): baixando de novo"),
            logged,
        )
        assertTrue(manager.state.value is UpdateState.Available)
    }

    @Test
    fun `apk whose version still differs ends in an error without installing`() {
        archiveVersion = null

        manager.checkOnLaunch()
        runBlocking { manager.downloadAndInstall(context) }

        val state = manager.state.value
        assertTrue(state is UpdateState.Error)
        assertEquals("APK baixado não corresponde à versão 1.2.0", (state as UpdateState.Error).message)
        assertEquals(2, applier.downloads)
        assertEquals(0, applier.installs)
        assertFalse(cacheFile("1.2.0").exists())
    }

    @Test
    fun `successful install removes the apks from older versions`() {
        val stale = cacheFile("1.1.0").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(4))
        }
        val legacy = File(File(context.cacheDir, "updates"), "update.apk").apply {
            writeBytes(ByteArray(4))
        }

        manager.checkOnLaunch()
        runBlocking { manager.downloadAndInstall(context) }

        assertEquals(1, applier.installs)
        assertFalse(stale.exists())
        assertFalse(legacy.exists())
        assertTrue(cacheFile("1.2.0").isFile)
    }

    @Test
    fun `apkMatchesRelease requires the exact version`() {
        assertTrue(apkMatchesRelease("1.2.0", "1.2.0"))
        assertFalse(apkMatchesRelease(null, "1.2.0"))
        assertFalse(apkMatchesRelease("1.4.1", "1.2.0"))
    }

    @Test
    fun `cache file name is per version and has no path separator`() {
        assertEquals("update-1.2.0.apk", apkFileName("1.2.0"))
        assertEquals("update-1.2.0-beta.1.apk", apkFileName("1.2.0-beta.1"))
        val traversal = apkFileName("../../etc/passwd")
        assertFalse(traversal.contains('/'))
        assertFalse(traversal.contains('\\'))
        assertTrue(traversal.startsWith("update-"))
    }

    private fun cacheFile(versionName: String): File =
        File(File(context.cacheDir, "updates"), apkFileName(versionName))

    private class FakeReleaseSource(
        var next: ReleaseInfo? = release(),
        override val currentVersion: String = CURRENT,
    ) : ReleaseSource {
        var calls = 0

        override suspend fun latest(): ReleaseInfo? {
            calls++
            return next
        }
    }

    private class FakeApkInstaller : ApkInstaller {
        var installs = 0
        var downloads = 0
        var failure: Exception? = null
        var observe: (() -> Unit)? = null

        override suspend fun download(info: ReleaseInfo, dest: File, onProgress: (Long, Long) -> Unit) {
            downloads++
            observe?.invoke()
            failure?.let { throw it }
            listOf(0L, 50L, 100L).forEach { done ->
                onProgress(done, 100)
                observe?.invoke()
            }
            dest.parentFile?.mkdirs()
            dest.writeBytes(ByteArray(info.apkSize.toInt()))
        }

        override fun install(context: Context, apk: File) {
            installs++
            observe?.invoke()
        }
    }
}
