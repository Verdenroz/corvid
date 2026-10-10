package com.verdenroz.corvid.core.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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
    fun `the newest notice seen starts at zero and is published once set`() =
        runTest {
            val prefs = preferences()
            runCurrent()
            assertEquals(0, prefs.settings.value.noticesSeen)

            prefs.setNoticesSeen(41)
            runCurrent()

            assertEquals(41, prefs.settings.value.noticesSeen)
        }

    @Test
    fun `a file written before notices existed reads as nothing seen and keeps its choices`() {
        val preferences = preferencesOf(stringPreferencesKey("theme") to "Dark", intPreferencesKey("auto_lock_minutes") to 15)

        assertEquals(DeviceSettings(ThemeChoice.Dark, AutoLock.FifteenMinutes, noticesSeen = 0), decode(preferences))
    }

    @Test
    fun `a device name is kept per server and absent until set`() =
        runTest {
            val prefs = preferences()
            assertEquals(null, prefs.deviceName("mail.example.com"))

            prefs.setDeviceName("mail.example.com", "laptop-1")

            assertEquals("laptop-1", prefs.deviceName("mail.example.com"))
            assertEquals(null, prefs.deviceName("other.example.com"))
        }

    @Test
    fun `a stored notice id is read back`() {
        assertEquals(9, decode(preferencesOf(longPreferencesKey("notices_seen") to 9L)).noticesSeen)
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
