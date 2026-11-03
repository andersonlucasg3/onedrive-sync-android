package com.anderson.onedrivesync.ui.vm

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.anderson.onedrivesync.App
import com.anderson.onedrivesync.R
import com.anderson.onedrivesync.db.ConflictRow
import com.anderson.onedrivesync.db.ConflictsDao
import com.anderson.onedrivesync.db.Db
import com.anderson.onedrivesync.db.PendingDao
import com.anderson.onedrivesync.db.PendingKind
import com.anderson.onedrivesync.db.PendingOp
import com.anderson.onedrivesync.sync.ConflictDecision
import com.anderson.onedrivesync.sync.SyncEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PendingUiState(
    val loading: Boolean = true,
    val conflicts: List<ConflictRow> = emptyList(),
    val deletions: List<PendingOp> = emptyList(),
    val busyConflicts: Set<Long> = emptySet(),
    val busyDeletions: Set<Long> = emptySet(),
    val approvingAll: Boolean = false,
    val errorMessage: String? = null,
    /** Bumped after every successful action so the shell can refresh its badge. */
    val completedActions: Int = 0,
) {
    val isEmpty: Boolean get() = conflicts.isEmpty() && deletions.isEmpty()
}

/**
 * Conflicts and pending deletions. The DAOs expose no Flow, so every action ends with a reload;
 * [SyncEngine] is the only writer of these tables and owns the mutex the actions wait on.
 *
 * Only the deletion kinds the user must confirm are shown: transfers are never queued.
 */
class PendingViewModel(
    private val context: Context,
    private val db: Db,
    private val engine: SyncEngine,
) : ViewModel() {
    private val _state = MutableStateFlow(PendingUiState())
    val state: StateFlow<PendingUiState> = _state.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        // The spinner only replaces content on the first load; a refresh keeps the list on screen.
        _state.update { it.copy(loading = it.isEmpty) }
        viewModelScope.launch {
            val conflicts = withContext(Dispatchers.IO) { ConflictsDao.list(db.readableDatabase) }
            val deletions = withContext(Dispatchers.IO) { deletions() }
            _state.update { it.copy(loading = false, conflicts = conflicts, deletions = deletions) }
        }
    }

    fun resolve(id: Long, decision: ConflictDecision) {
        if (id in _state.value.busyConflicts || _state.value.approvingAll) return
        _state.update { it.copy(busyConflicts = it.busyConflicts + id) }
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    engine.resolveConflict(id, decision)
                    // The engine keeps the row when it cannot resolve (missing side): say so
                    // instead of leaving the user with a conflict that silently stays queued.
                    if (ConflictsDao.get(db.readableDatabase, id) != null) {
                        context.getString(R.string.pending_resolve_failed)
                    } else {
                        null
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.message ?: context.getString(R.string.pending_action_error)
                }
            }
            _state.update {
                it.copy(
                    busyConflicts = it.busyConflicts - id,
                    errorMessage = error,
                    completedActions = if (error == null) it.completedActions + 1 else it.completedActions,
                )
            }
            reload()
        }
    }

    fun approve(id: Long) = mutateDeletion(id) { engine.approvePending(id) }

    fun reject(id: Long) = mutateDeletion(id) { engine.rejectPending(id) }

    /** Batch approval of every pending deletion; conflicts have their own per-item decision. */
    fun approveAll() {
        val current = _state.value
        if (current.approvingAll || current.deletions.isEmpty()) return
        val ids = current.deletions.map { it.id }
        _state.update { it.copy(approvingAll = true) }
        viewModelScope.launch {
            var failures = 0
            withContext(Dispatchers.IO) {
                for (id in ids) {
                    try {
                        engine.approvePending(id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        failures++
                    }
                }
            }
            _state.update {
                it.copy(
                    approvingAll = false,
                    errorMessage = if (failures > 0) {
                        context.getString(R.string.pending_approve_all_error, failures)
                    } else {
                        null
                    },
                    completedActions = if (failures < ids.size) it.completedActions + 1 else it.completedActions,
                )
            }
            reload()
        }
    }

    fun consumeError() {
        _state.update { it.copy(errorMessage = null) }
    }

    private fun mutateDeletion(id: Long, action: suspend () -> Unit) {
        if (id in _state.value.busyDeletions || _state.value.approvingAll) return
        _state.update { it.copy(busyDeletions = it.busyDeletions + id) }
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    action()
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.message ?: context.getString(R.string.pending_action_error)
                }
            }
            _state.update {
                it.copy(
                    busyDeletions = it.busyDeletions - id,
                    errorMessage = error,
                    completedActions = if (error == null) it.completedActions + 1 else it.completedActions,
                )
            }
            reload()
        }
    }

    private fun deletions(): List<PendingOp> =
        PendingDao.list(db.readableDatabase).filter {
            it.kind == PendingKind.DELETE_LOCAL || it.kind == PendingKind.DELETE_REMOTE
        }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = context.applicationContext as App
                PendingViewModel(app, app.container.db, app.container.syncEngine)
            }
        }
    }
}
