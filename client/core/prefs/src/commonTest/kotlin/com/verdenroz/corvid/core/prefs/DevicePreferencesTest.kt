package com.verdenroz.corvid.core.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.verdenroz.corvid.core.testing.InMemoryPreferencesStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

private fun TestScope.preferences(store: DataStore<Preferences> = InMemoryPreferencesStore()) =
    DevicePreferences(store, backgroundScope, DeviceSettings())

@OptIn(ExperimentalCoroutinesApi::class)
class DevicePreferencesTest {
    @Test
    fun `a fresh device follows the system theme and locks after five minutes`() =
        runTest {
            val prefs = preferences()
            runCurrent()

            assertEquals(DeviceSettings(ThemeChoice.System, AutoLock.FiveMinutes), prefs.settings.value)
        }

    @Test
    fun `a chosen theme is published`() =
        runTest {
            val prefs = preferences()

            prefs.setTheme(ThemeChoice.Dark)
            runCurrent()

            assertEquals(ThemeChoice.Dark, prefs.settings.value.theme)
        }

    @Test
    fun `a chosen auto-lock is published and leaves the theme alone`() =
        runTest {
            val prefs = preferences()
            prefs.setTheme(ThemeChoice.Light)

            prefs.setAutoLock(AutoLock.ThirtyMinutes)
            runCurrent()

            assertEquals(DeviceSettings(ThemeChoice.Light, AutoLock.ThirtyMinutes), prefs.settings.value)
        }

    @Test
    fun `stored values the app does not offer fall back to the defaults`() {
        val preferences = preferencesOf(stringPreferencesKey("theme") to "Sepia", intPreferencesKey("auto_lock_minutes") to 7)

        assertEquals(DeviceSettings(), decode(preferences))
    }

    @Test
    fun `each auto-lock choice maps to its minutes`() {
        assertEquals(listOf(1.minutes, 5.minutes, 15.minutes, 30.minutes), AutoLock.entries.map { it.timeout })
    }
}
