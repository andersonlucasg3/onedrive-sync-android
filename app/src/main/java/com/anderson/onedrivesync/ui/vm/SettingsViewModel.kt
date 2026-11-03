package com.anderson.onedrivesync.ui.vm

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.anderson.onedrivesync.App
import com.anderson.onedrivesync.R
import com.anderson.onedrivesync.auth.AuthManager
import com.anderson.onedrivesync.core.Prefs
import com.anderson.onedrivesync.db.ConflictsDao
import com.anderson.onedrivesync.db.Db
import com.anderson.onedrivesync.db.LogDao
import com.anderson.onedrivesync.db.Pair
import com.anderson.onedrivesync.db.PairsDao
import com.anderson.onedrivesync.db.PendingDao
import com.anderson.onedrivesync.db.StateDao
import com.anderson.onedrivesync.graph.GraphClient
import com.anderson.onedrivesync.hyperos.HyperOsPermissions
import com.anderson.onedrivesync.log.shareLog
import com.anderson.onedrivesync.log.writeLogFile
import com.anderson.onedrivesync.sync.SyncEngine
import com.anderson.onedrivesync.sync.SyncState
import com.anderson.onedrivesync.update.ReleaseInfo
import com.anderson.onedrivesync.update.UpdateApplier
import com.anderson.onedrivesync.update.UpdateChecker
import com.anderson.onedrivesync.update.isNewerVersion
import com.anderson.onedrivesync.work.Scheduler
import com.anderson.onedrivesync.work.SyncService
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Diagnostics summary: everything support would ask for, gathered locally. */
data class DiagnosticsUiState(
    val loading: Boolean = true,
    val signedIn: Boolean = false,
    val pairs: List<Pair> = emptyList(),
    val pendingCount: Int = 0,
    val conflictCount: Int = 0,
    val logLines: Int = 0,
    val checking: Boolean = false,
    val connectionResult: String? = null,
    val connectionError: String? = null,
    val sharing: Boolean = false,
    val resyncing: Boolean = false,
)

data class SettingsUiState(
    val syncIntervalMinutes: Int = 30,
    val wifiOnly: Boolean = false,
    val checklist: HyperOsPermissions.Checklist = HyperOsPermissions.Checklist(
        allFiles = false,
        notifications = false,
        battery = false,
        autostart = false,
    ),
    val diagnostics: DiagnosticsUiState = DiagnosticsUiState(),
    val errorMessage: String? = null,
)

/** State of the "Atualizações" card. */
sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object UpToDate : UpdateUiState
    data class Available(val info: ReleaseInfo) : UpdateUiState
    data class Downloading(val progress: Int) : UpdateUiState
    data object Installing : UpdateUiState
    data class Error(val message: String) : UpdateUiState
}

/**
 * Settings values live in [Prefs] (the worker reads them directly); the checklist and the
 * diagnostics summary are re-read on every resume because the permission screens are separate
 * activities and the background work changes the counts.
 */
class SettingsViewModel(
    private val context: Context,
    private val prefs: Prefs,
    private val engine: SyncEngine,
    private val db: Db,
    private val graphClient: GraphClient,
    private val authManager: AuthManager,
    private val updateChecker: UpdateChecker,
    private val updateApplier: UpdateApplier,
) : ViewModel() {
    private val _state = MutableStateFlow(
        SettingsUiState(
            syncIntervalMinutes = prefs.syncIntervalMinutes,
            wifiOnly = prefs.wifionly,
        ),
    )
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    /** Drives the "Cancelar sincronização" button: it only exists while the engine is busy. */
    val syncState: StateFlow<SyncState> = engine.state

    private val _updateState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

    /** Release already on disk: re-opening the installer must not download the APK again. */
    private var downloadedFor: String? = null

    init {
        refreshChecklist()
        refreshDiagnostics()
    }

    fun setInterval(minutes: Int) {
        prefs.syncIntervalMinutes = minutes
        _state.update { it.copy(syncIntervalMinutes = minutes) }
        reschedule()
    }

    fun setWifiOnly(enabled: Boolean) {
        prefs.wifionly = enabled
        _state.update { it.copy(wifiOnly = enabled) }
    }

    fun refreshChecklist() {
        viewModelScope.launch {
            val checklist = withContext(Dispatchers.IO) { HyperOsPermissions.checklist(context) }
            _state.update { it.copy(checklist = checklist) }
        }
    }

    fun syncNow() {
        try {
            SyncService.start(context)
        } catch (e: Exception) {
            _state.update { it.copy(errorMessage = e.message ?: context.getString(R.string.settings_error)) }
        }
    }

    fun cancelSync() {
        engine.cancel()
    }

    fun reschedule() {
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    Scheduler.ensurePeriodic(context)
                    null
                } catch (e: Exception) {
                    e.message ?: context.getString(R.string.settings_error)
                }
            }
            if (error != null) _state.update { it.copy(errorMessage = error) }
        }
    }

    fun consumeError() {
        _state.update { it.copy(errorMessage = null) }
    }

    fun refreshDiagnostics() {
        viewModelScope.launch {
            val snapshot: Result<DiagnosticsSnapshot> = withContext(Dispatchers.IO) {
                try {
                    Result.success(loadDiagnostics())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            snapshot.fold(
                onSuccess = { data ->
                    _state.update {
                        it.copy(
                            diagnostics = it.diagnostics.copy(
                                loading = false,
                                signedIn = data.signedIn,
                                pairs = data.pairs,
                                pendingCount = data.pendingCount,
                                conflictCount = data.conflictCount,
                                logLines = data.logLines,
                            ),
                        )
                    }
                },
                onFailure = { failure ->
                    _state.update {
                        it.copy(
                            diagnostics = it.diagnostics.copy(loading = false),
                            errorMessage = failure.message ?: context.getString(R.string.settings_error),
                        )
                    }
                },
            )
        }
    }

    /** Real Graph call: shows the account behind the token or the actual failure message. */
    fun testConnection() {
        if (_state.value.diagnostics.checking) return
        _state.update {
            it.copy(diagnostics = it.diagnostics.copy(checking = true, connectionResult = null, connectionError = null))
        }
        viewModelScope.launch {
            try {
                val user = graphClient.me()
                val label = listOfNotNull(
                    user.displayName?.takeIf { it.isNotBlank() },
                    user.userPrincipalName?.takeIf { it.isNotBlank() },
                ).distinct().joinToString(" · ")
                    .ifEmpty { context.getString(R.string.settings_diag_signed_in) }
                _state.update {
                    it.copy(
                        diagnostics = it.diagnostics.copy(
                            checking = false,
                            connectionResult = context.getString(R.string.settings_diag_connection_ok, label),
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        diagnostics = it.diagnostics.copy(
                            checking = false,
                            connectionError = e.message ?: context.getString(R.string.settings_error),
                        ),
                    )
                }
            }
        }
    }

    fun shareLogs() {
        if (_state.value.diagnostics.sharing) return
        _state.update { it.copy(diagnostics = it.diagnostics.copy(sharing = true)) }
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                try {
                    writeLogFile(context, db)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
            _state.update {
                it.copy(
                    diagnostics = it.diagnostics.copy(sharing = false),
                    errorMessage = if (file == null) context.getString(R.string.log_share_error) else null,
                )
            }
            if (file != null) shareLog(context, file)
        }
    }

    /**
     * Drops the drive-wide delta cursor so the next cycle re-enumerates everything. Nothing is
     * deleted: a fresh enumeration only makes the planner see items it already indexed.
     */
    fun forceFullResync() {
        if (_state.value.diagnostics.resyncing) return
        _state.update { it.copy(diagnostics = it.diagnostics.copy(resyncing = true)) }
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    StateDao.remove(db.writableDatabase, SyncEngine.KEY_DELTA_LINK)
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.message ?: context.getString(R.string.settings_error)
                }
            }
            _state.update {
                it.copy(diagnostics = it.diagnostics.copy(resyncing = false), errorMessage = error)
            }
            if (error == null) {
                syncNow()
                refreshDiagnostics()
            }
        }
    }

    fun checkUpdates() {
        if (_updateState.value is UpdateUiState.Checking) return
        _updateState.value = UpdateUiState.Checking
        viewModelScope.launch {
            val release = updateChecker.latest()
            _updateState.value = when {
                release == null -> UpdateUiState.Error(context.getString(R.string.settings_updates_error))
                !isNewerVersion(updateChecker.currentVersion, release.versionName) -> UpdateUiState.UpToDate
                else -> UpdateUiState.Available(release)
            }
        }
    }

    /** Downloads the release APK into cacheDir/updates and opens the system installer. */
    fun downloadAndInstall() {
        val info = (_updateState.value as? UpdateUiState.Available)?.info ?: return
        viewModelScope.launch {
            val apk = File(File(context.cacheDir, UPDATE_DIR), UPDATE_APK)
            val cached = downloadedFor == info.apkUrl && apk.isFile && apk.length() == info.apkSize
            val error = if (cached) {
                null
            } else {
                _updateState.value = UpdateUiState.Downloading(0)
                withContext(Dispatchers.IO) {
                    try {
                        updateApplier.download(info, apk) { done, total ->
                            val percent = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
                            _updateState.value = UpdateUiState.Downloading(percent)
                        }
                        null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        e.message ?: context.getString(R.string.settings_updates_error)
                    }
                }
            }
            if (error != null) {
                _updateState.value = UpdateUiState.Error(error)
                return@launch
            }
            downloadedFor = info.apkUrl
            _updateState.value = UpdateUiState.Installing
            updateApplier.install(context, apk)
            // The installer (or the unknown-sources screen) took over; allow trying again.
            _updateState.value = UpdateUiState.Available(info)
        }
    }

    private fun loadDiagnostics(): DiagnosticsSnapshot {
        val database = db.readableDatabase
        return DiagnosticsSnapshot(
            signedIn = authManager.isSignedIn(),
            pairs = PairsDao.list(database),
            pendingCount = PendingDao.list(database).size,
            conflictCount = ConflictsDao.list(database).size,
            logLines = LogDao.count(database),
        )
    }

    private class DiagnosticsSnapshot(
        val signedIn: Boolean,
        val pairs: List<Pair>,
        val pendingCount: Int,
        val conflictCount: Int,
        val logLines: Int,
    )

    companion object {
        private const val UPDATE_DIR = "updates"
        private const val UPDATE_APK = "update.apk"

        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = context.applicationContext as App
                SettingsViewModel(
                    context = app,
                    prefs = app.container.prefs,
                    engine = app.container.syncEngine,
                    db = app.container.db,
                    graphClient = app.container.graphClient,
                    authManager = app.container.authManager,
                    updateChecker = app.container.updateChecker,
                    updateApplier = app.container.updateApplier,
                )
            }
        }
    }
}
