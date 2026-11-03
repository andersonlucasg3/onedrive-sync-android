package com.anderson.onedrivesync.ui.vm

import android.content.Context
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.anderson.onedrivesync.App
import com.anderson.onedrivesync.R
import com.anderson.onedrivesync.db.ConflictsDao
import com.anderson.onedrivesync.db.Db
import com.anderson.onedrivesync.db.Pair
import com.anderson.onedrivesync.db.PairsDao
import com.anderson.onedrivesync.db.PendingDao
import com.anderson.onedrivesync.db.StateDao
import com.anderson.onedrivesync.sync.CycleResult
import com.anderson.onedrivesync.sync.CycleResultKeys
import com.anderson.onedrivesync.sync.decodeCycleResult
import com.anderson.onedrivesync.work.SyncService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PairRow(
    val pair: Pair,
    val pendingCount: Int,
    val conflictCount: Int,
    val result: CycleResult? = null,
)

data class PairsUiState(
    val loading: Boolean = true,
    val rows: List<PairRow> = emptyList(),
    val storageGranted: Boolean = false,
    val errorMessage: String? = null,
    val globalResult: CycleResult? = null,
)

/**
 * Pair list plus per-pair counters. The DAOs do not expose Flow, so the screen reloads on resume and
 * a light 2 s poll covers background syncs that finish while the list is visible.
 */
class PairsViewModel(
    private val context: Context,
    private val db: Db,
) : ViewModel() {
    private val _state = MutableStateFlow(PairsUiState())
    val state: StateFlow<PairsUiState> = _state.asStateFlow()

    private var pollingJob: Job? = null

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { load() }
            _state.update {
                it.copy(
                    loading = false,
                    rows = loaded.rows,
                    globalResult = loaded.globalResult,
                    storageGranted = Environment.isExternalStorageManager(),
                )
            }
        }
    }

    fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                val loaded = withContext(Dispatchers.IO) { load() }
                _state.update { it.copy(loading = false, rows = loaded.rows, globalResult = loaded.globalResult) }
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun sync(pairId: Long) {
        SyncService.start(context, pairId)
    }

    fun setEnabled(pairId: Long, enabled: Boolean) {
        mutate { PairsDao.setEnabled(db.writableDatabase, pairId, enabled) }
    }

    fun delete(pairId: Long) {
        mutate { PairsDao.delete(db.writableDatabase, pairId) }
    }

    fun consumeError() {
        _state.update { it.copy(errorMessage = null) }
    }

    private fun mutate(action: () -> Unit) {
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    action()
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.message ?: context.getString(R.string.pairs_action_error)
                }
            }
            if (error != null) _state.update { it.copy(errorMessage = error) }
            reload()
        }
    }

    private fun load(): Loaded {
        val database = db.readableDatabase
        val pending = PendingDao.list(database).groupingBy { it.pairId }.eachCount()
        val conflicts = ConflictsDao.list(database).groupingBy { it.pairId }.eachCount()
        val rows = PairsDao.list(database).map { pair ->
            PairRow(
                pair = pair,
                pendingCount = pending[pair.id] ?: 0,
                conflictCount = conflicts[pair.id] ?: 0,
                result = decodeCycleResult(StateDao.get(database, CycleResultKeys.pair(pair.id))),
            )
        }
        return Loaded(
            rows = rows,
            globalResult = decodeCycleResult(StateDao.get(database, CycleResultKeys.GLOBAL)),
        )
    }

    private data class Loaded(val rows: List<PairRow>, val globalResult: CycleResult?)

    companion object {
        private const val POLL_INTERVAL_MS = 2_000L

        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = context.applicationContext as App
                PairsViewModel(app, app.container.db)
            }
        }
    }
}
