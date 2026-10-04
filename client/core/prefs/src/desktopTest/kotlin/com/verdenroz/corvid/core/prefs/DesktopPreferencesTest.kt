package com.verdenroz.corvid.core.prefs

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopPreferencesTest {
    @Test
    fun `settings written to the file survive a new store over it`() =
        runTest {
            val file = File(createTempDirectory().toFile(), "device.preferences_pb")
            val job = Job()
            val writing = CoroutineScope(job)
            val prefs = DevicePreferences(PreferenceDataStoreFactory.create(scope = writing) { file }, writing, DeviceSettings())
            prefs.setTheme(ThemeChoice.Dark)
            prefs.setAutoLock(AutoLock.FifteenMinutes)
            job.cancelAndJoin()

            val reopened = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }

            assertEquals(DeviceSettings(ThemeChoice.Dark, AutoLock.FifteenMinutes), decode(reopened.data.first()))
        }

    @Test
    fun `linux keeps the file under XDG_CONFIG_HOME when set and dot config otherwise`() {
        assertEquals(File("/xdg"), userConfigDir("Linux", { if (it == "XDG_CONFIG_HOME") "/xdg" else null }, "/home/a"))
        assertEquals(File("/home/a/.config"), userConfigDir("Linux", { null }, "/home/a"))
    }

    @Test
    fun `mac and windows use their own per-user directories`() {
        assertEquals(File("/Users/a/Library/Application Support"), userConfigDir("Mac OS X", { null }, "/Users/a"))
        assertEquals(File("C:\\Roaming"), userConfigDir("Windows 11", { if (it == "APPDATA") "C:\\Roaming" else null }, "C:\\Users\\a"))
    }
}
