package com.anderson.onedrivesync.ui.vm

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.anderson.onedrivesync.App
import com.anderson.onedrivesync.R
import com.anderson.onedrivesync.db.Db
import com.anderson.onedrivesync.db.LogDao
import com.anderson.onedrivesync.db.LogRow
import com.anderson.onedrivesync.log.shareLog
import com.anderson.onedrivesync.log.writeLogFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LogLevelFilter { ALL, INFO, WARN, ERROR }

data class LogUiState(
    val loading: Boolean = true,
    val rows: List<LogRow> = emptyList(),
    val filter: LogLevelFilter = LogLevelFilter.ALL,
    val query: String = "",
    val errorMessage: String? = null,
    val sharing: Boolean = false,
    val message: String? = null,
) {
    /** Level filter plus the case-insensitive search over tag and message, applied in memory. */
    val visible: List<LogRow>
        get() = rows.filter { row ->
            (filter == LogLevelFilter.ALL || row.level.equals(filter.name, ignoreCase = true)) &&
                (query.isBlank() || row.tag.contains(query, ignoreCase = true) ||
                    row.msg.contains(query, ignoreCase = true))
        }
}

/** Newest first, at most [LIMIT] rows: the screen is a window over the persisted log, not a file. */
class LogViewModel(
    private val context: Context,
    private val db: Db,
) : ViewModel() {
    private val _state = MutableStateFlow(LogUiState())
    val state: StateFlow<LogUiState> = _state.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    val rows = LogDao.recent(db.readableDatabase, LIMIT)
                    _state.update { it.copy(loading = false, rows = rows) }
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.message ?: context.getString(R.string.log_error)
                }
            }
            if (error != null) _state.update { it.copy(loading = false, errorMessage = error) }
        }
    }

    fun setFilter(filter: LogLevelFilter) {
        _state.update { it.copy(filter = filter) }
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
    }

    fun clear() {
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    LogDao.clear(db.writableDatabase)
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.message ?: context.getString(R.string.log_error)
                }
            }
            _state.update {
                it.copy(rows = if (error == null) emptyList() else it.rows, errorMessage = error)
            }
        }
    }

    fun consumeError() {
        _state.update { it.copy(errorMessage = null) }
    }

    /** Exports the full persisted log (the screen filter/search do not apply) and shares the file. */
    fun share() {
        if (_state.value.sharing) return
        _state.update { it.copy(sharing = true) }
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
            if (file == null) {
                _state.update {
                    it.copy(sharing = false, message = context.getString(R.string.log_share_error))
                }
                return@launch
            }
            shareLog(context, file)
            _state.update {
                it.copy(sharing = false, message = context.getString(R.string.log_share_done))
            }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    companion object {
        private const val LIMIT = 300

        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = context.applicationContext as App
                LogViewModel(app, app.container.db)
            }
        }
    }
}
