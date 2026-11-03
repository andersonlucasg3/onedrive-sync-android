package com.anderson.onedrivesync.ui.vm

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.anderson.onedrivesync.App
import com.anderson.onedrivesync.auth.AuthManager
import com.anderson.onedrivesync.auth.AuthState
import com.anderson.onedrivesync.core.DEFAULT_TENANT
import com.anderson.onedrivesync.core.Prefs
import com.anderson.onedrivesync.core.normalizeTenant
import com.anderson.onedrivesync.hyperos.HyperOsPermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class OnboardingUiState(
    val checklist: HyperOsPermissions.Checklist = HyperOsPermissions.Checklist(
        allFiles = false,
        notifications = false,
        battery = false,
        autostart = false,
    ),
    val signedIn: Boolean = false,
    val tenant: String = DEFAULT_TENANT,
) {
    val canContinue: Boolean get() = checklist.allFiles && signedIn
}

/**
 * Real permission state and real account state, never a local "onboarding done" flag: the start
 * destination is derived from the same checks, so they cannot disagree.
 */
class OnboardingViewModel(
    private val context: Context,
    private val auth: AuthManager,
    private val prefs: Prefs,
) : ViewModel() {
    private val _state = MutableStateFlow(OnboardingUiState(tenant = prefs.tenant))
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            auth.state.collect { authState -> applySignedIn(authState) }
        }
    }

    /** Re-reads the checklist after the user comes back from a system settings screen. */
    fun refresh() {
        viewModelScope.launch {
            val checklist = withContext(Dispatchers.IO) { HyperOsPermissions.checklist(context) }
            _state.update { it.copy(checklist = checklist) }
            applySignedIn(auth.state.value)
        }
    }

    /** Same normalization as Settings: the tenant never stays blank. */
    fun setTenant(value: String) {
        val normalized = normalizeTenant(value)
        prefs.tenant = normalized
        _state.update { it.copy(tenant = normalized) }
    }

    private suspend fun applySignedIn(authState: AuthState) {
        val signedIn = authState is AuthState.SignedIn ||
            withContext(Dispatchers.IO) { auth.isSignedIn() }
        _state.update { it.copy(signedIn = signedIn) }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = context.applicationContext as App
                OnboardingViewModel(app, app.container.authManager, app.container.prefs)
            }
        }
    }
}
