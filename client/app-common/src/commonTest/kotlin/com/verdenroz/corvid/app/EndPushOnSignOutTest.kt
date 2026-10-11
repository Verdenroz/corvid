package com.verdenroz.corvid.app

import com.verdenroz.corvid.core.prefs.DevicePreferences
import com.verdenroz.corvid.core.prefs.DeviceSettings
import com.verdenroz.corvid.core.prefs.PushSettings
import com.verdenroz.corvid.core.prefs.PushStatus
import com.verdenroz.corvid.core.prefs.PushSubscription
import com.verdenroz.corvid.core.testing.InMemoryPreferencesStore
import com.verdenroz.corvid.feature.settings.BackgroundPush
import com.verdenroz.corvid.feature.settings.PushTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private class CountingPush : BackgroundPush {
    var unregistrations = 0

    override fun transport() = PushTransport.Google

    override fun register(vapid: String) = Unit

    override fun registerOwnConnection() = PushSubscription("corvid-stream", "p256", "auth")

    override fun unregister() {
        unregistrations++
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun TestScope.preferences(): DevicePreferences =
    DevicePreferences(InMemoryPreferencesStore(), backgroundScope, DeviceSettings()).also { runCurrent() }

@OptIn(ExperimentalCoroutinesApi::class)
class EndPushOnSignOutTest {
    @Test
    fun `leaving a session with push on unregisters and waits to tell the server`() =
        runTest {
            val preferences = preferences()
            preferences.setPushSubscription(PushSubscription("https://fcm.googleapis.com/fcm/send/abc", "p256", "auth"))
            runCurrent()
            val push = CountingPush()

            endPushOnSignOut(flowOf(false, true, false), push, preferences)
            runCurrent()

            assertEquals(1, push.unregistrations)
            assertEquals(PushSettings(PushStatus.Removing), preferences.settings.value.push)
        }

    @Test
    fun `starting signed out, or signing out with push already off, changes nothing`() =
        runTest {
            val preferences = preferences()
            val push = CountingPush()

            endPushOnSignOut(flowOf(false, true, false), push, preferences)
            runCurrent()

            assertEquals(0, push.unregistrations)
            assertEquals(PushSettings(), preferences.settings.value.push)
        }
}
