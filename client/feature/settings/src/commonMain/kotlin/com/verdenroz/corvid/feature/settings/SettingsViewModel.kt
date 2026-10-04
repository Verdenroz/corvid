package com.verdenroz.corvid.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdenroz.corvid.core.prefs.AutoLock
import com.verdenroz.corvid.core.prefs.DevicePreferences
import com.verdenroz.corvid.core.prefs.DeviceSettings
import com.verdenroz.corvid.core.prefs.ThemeChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: DeviceSettings,
    val confirmingSignOut: Boolean = false,
)

class SettingsViewModel(
    private val preferences: DevicePreferences,
    private val onLock: () -> Unit,
    private val onSignOut: () -> Unit,
) : ViewModel() {
    private val confirmingSignOut = MutableStateFlow(false)

    val state: StateFlow<SettingsUiState> =
        combine(preferences.settings, confirmingSignOut, ::SettingsUiState)
            .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState(preferences.settings.value))

    fun selectTheme(theme: ThemeChoice) {
        viewModelScope.launch { preferences.setTheme(theme) }
    }

    fun selectAutoLock(autoLock: AutoLock) {
        viewModelScope.launch { preferences.setAutoLock(autoLock) }
    }

    fun lockNow() = onLock()

    fun askSignOut() {
        confirmingSignOut.value = true
    }

    fun keepSignedIn() {
        confirmingSignOut.value = false
    }

    fun confirmSignOut() = onSignOut()
}
