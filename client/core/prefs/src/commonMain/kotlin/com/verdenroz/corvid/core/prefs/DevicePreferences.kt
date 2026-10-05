package com.verdenroz.corvid.core.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private val THEME = stringPreferencesKey("theme")
private val AUTO_LOCK_MINUTES = intPreferencesKey("auto_lock_minutes")
private val NOTICES_SEEN = longPreferencesKey("notices_seen")

/**
 * [settings] starts at [initial], which the platform reads synchronously before the first frame
 * so the lock screen already follows the chosen theme.
 */
class DevicePreferences(
    private val store: DataStore<Preferences>,
    scope: CoroutineScope,
    initial: DeviceSettings,
) {
    val settings: StateFlow<DeviceSettings> = store.data.map(::decode).stateIn(scope, SharingStarted.Eagerly, initial)

    suspend fun setTheme(theme: ThemeChoice) {
        store.edit { it[THEME] = theme.name }
    }

    suspend fun setAutoLock(autoLock: AutoLock) {
        store.edit { it[AUTO_LOCK_MINUTES] = autoLock.minutes }
    }

    suspend fun setNoticesSeen(id: Long) {
        store.edit { it[NOTICES_SEEN] = id }
    }
}

internal fun decode(preferences: Preferences): DeviceSettings {
    val defaults = DeviceSettings()
    return DeviceSettings(
        theme = ThemeChoice.entries.firstOrNull { it.name == preferences[THEME] } ?: defaults.theme,
        autoLock = AutoLock.entries.firstOrNull { it.minutes == preferences[AUTO_LOCK_MINUTES] } ?: defaults.autoLock,
        noticesSeen = preferences[NOTICES_SEEN] ?: defaults.noticesSeen,
    )
}
