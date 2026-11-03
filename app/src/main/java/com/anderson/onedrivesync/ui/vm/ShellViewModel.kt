package com.anderson.onedrivesync.ui.vm

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.anderson.onedrivesync.App
import com.anderson.onedrivesync.db.ConflictsDao
import com.anderson.onedrivesync.db.Db
import com.anderson.onedrivesync.db.PendingDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Counters the shell needs outside any screen (the bottom-bar badge). The DAOs expose no Flow, so
 * this reloads whenever navigation changes instead of observing the database.
 */
class ShellViewModel(private val db: Db) : ViewModel() {
    private val _pendingTotal = MutableStateFlow(0)
    val pendingTotal: StateFlow<Int> = _pendingTotal.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _pendingTotal.value = withContext(Dispatchers.IO) {
                val database = db.readableDatabase
                PendingDao.list(database).size + ConflictsDao.list(database).size
            }
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer { ShellViewModel((context.applicationContext as App).container.db) }
        }
    }
}
