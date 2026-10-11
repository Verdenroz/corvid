package com.verdenroz.corvid.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdenroz.corvid.core.jmap.JmapException
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

/** [backgroundPush] is null where the platform has no background push. */
data class SettingsUiState(
    val settings: DeviceSettings,
    val confirmingSignOut: Boolean = false,
    val backgroundPush: BackgroundPushLine? = null,
)

class SettingsViewModel(
    private val preferences: DevicePreferences,
    private val onLock: () -> Unit,
    private val onSignOut: () -> Unit,
    private val askNotificationPermission: () -> Unit = {},
    private val backgroundPush: BackgroundPush? = null,
    private val vapidKey: suspend () -> String? = { null },
) : ViewModel() {
    private val confirmingSignOut = MutableStateFlow(false)
    private val unavailable = MutableStateFlow(false)

    val state: StateFlow<SettingsUiState> =
        combine(preferences.settings, confirmingSignOut, unavailable, ::uiState)
            .stateIn(viewModelScope, SharingStarted.Eagerly, uiState(preferences.settings.value, false, false))

    private fun uiState(
        settings: DeviceSettings,
        confirmingSignOut: Boolean,
        unavailable: Boolean,
    ) = SettingsUiState(settings, confirmingSignOut, backgroundPush?.let { backgroundPushLine(settings.push.status, unavailable) })

    fun selectTheme(theme: ThemeChoice) {
        viewModelScope.launch { preferences.setTheme(theme) }
    }

    fun selectAutoLock(autoLock: AutoLock) {
        viewModelScope.launch { preferences.setAutoLock(autoLock) }
    }

    /** Turning them on asks for the platform's notification permission, the one moment the user has asked for them. */
    fun setNewMailNotifications(on: Boolean) {
        if (on) askNotificationPermission()
        viewModelScope.launch { preferences.setNewMailNotifications(on) }
    }

    /** Registering is recorded before the platform is asked for an endpoint, since the endpoint may arrive at once. */
    fun setBackgroundPush(on: Boolean) {
        val push = backgroundPush ?: return
        if (on == state.value.backgroundPush?.on) return
        unavailable.value = false
        if (!on) {
            push.unregister()
            viewModelScope.launch { preferences.removePush() }
            return
        }
        askNotificationPermission()
        if (!push.available()) {
            unavailable.value = true
            return
        }
        viewModelScope.launch {
            preferences.setPushRegistering()
            val vapid =
                try {
                    vapidKey()
                } catch (e: JmapException) {
                    return@launch preferences.setPushFailed()
                }
            if (vapid == null) preferences.setPushOffOnServer() else push.register(vapid)
        }
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
