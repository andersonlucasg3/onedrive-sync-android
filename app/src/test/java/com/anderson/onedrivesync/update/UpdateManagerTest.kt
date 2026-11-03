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
 * from Robolectric.
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
        var failure: Exception? = null
        var observe: (() -> Unit)? = null

        override suspend fun download(info: ReleaseInfo, dest: File, onProgress: (Long, Long) -> Unit) {
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
