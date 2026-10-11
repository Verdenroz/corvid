package com.verdenroz.corvid.feature.settings

import com.verdenroz.corvid.core.jmap.JmapError
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.prefs.AutoLock
import com.verdenroz.corvid.core.prefs.DevicePreferences
import com.verdenroz.corvid.core.prefs.DeviceSettings
import com.verdenroz.corvid.core.prefs.OWN_CONNECTION
import com.verdenroz.corvid.core.prefs.PushStatus
import com.verdenroz.corvid.core.prefs.PushSubscription
import com.verdenroz.corvid.core.prefs.ThemeChoice
import com.verdenroz.corvid.core.testing.InMemoryPreferencesStore
import com.verdenroz.corvid.core.testing.ViewModelTestScope
import com.verdenroz.corvid.core.testing.runViewModelTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val VAPID = "BNvapidkey"

private class Calls {
    var locks = 0
    var signOuts = 0
    var permissionAsks = 0
}

private class FakeBackgroundPush(
    val transport: PushTransport = PushTransport.Google,
    val arriving: PushSubscription? = null,
) : BackgroundPush {
    var registrations = 0
    var ownRegistrations = 0
    var unregistrations = 0
    var vapid: String? = null
    lateinit var preferences: DevicePreferences

    override fun transport() = transport

    override fun registerOwnConnection(): PushSubscription {
        ownRegistrations++
        return PushSubscription(OWN_CONNECTION, "p256", "auth")
    }

    override fun register(vapid: String) {
        registrations++
        this.vapid = vapid
        arriving?.let {
            CoroutineScope(
                Dispatchers.Main,
            ).launch(start = CoroutineStart.UNDISPATCHED) { preferences.setPushSubscription(it) }
        }
    }

    override fun unregister() {
        unregistrations++
    }
}

private fun ViewModelTestScope.viewModel(
    calls: Calls = Calls(),
    push: FakeBackgroundPush? = null,
    preferences: DevicePreferences = DevicePreferences(InMemoryPreferencesStore(), CoroutineScope(Dispatchers.Main), DeviceSettings()),
    vapidKey: suspend () -> String? = { VAPID },
): SettingsViewModel {
    push?.preferences = preferences
    return track(SettingsViewModel(preferences, { calls.locks++ }, { calls.signOuts++ }, { calls.permissionAsks++ }, push, vapidKey))
}

class SettingsViewModelTest {
    @Test
    fun `choosing a theme and an auto-lock shows them in the state`() =
        runViewModelTest {
            val viewModel = viewModel()

            viewModel.selectTheme(ThemeChoice.Dark)
            viewModel.selectAutoLock(AutoLock.FifteenMinutes)

            assertEquals(DeviceSettings(ThemeChoice.Dark, AutoLock.FifteenMinutes), viewModel.state.value.settings)
        }

    @Test
    fun `new mail notifications start on and turning them off asks for no permission`() =
        runViewModelTest {
            val calls = Calls()
            val viewModel = viewModel(calls)
            assertTrue(viewModel.state.value.settings.newMailNotifications)

            viewModel.setNewMailNotifications(false)

            assertFalse(viewModel.state.value.settings.newMailNotifications)
            assertEquals(0, calls.permissionAsks)
        }

    @Test
    fun `turning new mail notifications back on asks for the notification permission`() =
        runViewModelTest {
            val calls = Calls()
            val viewModel = viewModel(calls)
            viewModel.setNewMailNotifications(false)

            viewModel.setNewMailNotifications(true)

            assertTrue(viewModel.state.value.settings.newMailNotifications)
            assertEquals(1, calls.permissionAsks)
        }

    @Test
    fun `lock now calls the lock action once`() =
        runViewModelTest {
            val calls = Calls()
            viewModel(calls).lockNow()

            assertEquals(1, calls.locks)
        }

    @Test
    fun `sign out asks first and only signs out once confirmed`() =
        runViewModelTest {
            val calls = Calls()
            val viewModel = viewModel(calls)

            viewModel.askSignOut()
            assertTrue(viewModel.state.value.confirmingSignOut)
            assertEquals(0, calls.signOuts)

            viewModel.confirmSignOut()
            assertEquals(1, calls.signOuts)
        }

    @Test
    fun `cancelling the sign-out question keeps the device signed in`() =
        runViewModelTest {
            val calls = Calls()
            val viewModel = viewModel(calls)

            viewModel.askSignOut()
            viewModel.keepSignedIn()

            assertFalse(viewModel.state.value.confirmingSignOut)
            assertEquals(0, calls.signOuts)
        }

    @Test
    fun `a platform without background push shows no row for it`() =
        runViewModelTest {
            assertNull(viewModel().state.value.backgroundPush)
        }

    @Test
    fun `background notifications start off`() =
        runViewModelTest {
            assertEquals(BackgroundPushState(PushStatus.Off, false), viewModel(push = FakeBackgroundPush()).state.value.backgroundPush)
        }

    @Test
    fun `turning background notifications on registers under the server's VAPID key and waits for the endpoint`() =
        runViewModelTest {
            val calls = Calls()
            val push = FakeBackgroundPush()
            val viewModel = viewModel(calls, push)

            viewModel.setBackgroundPush(true)

            assertEquals(1, push.registrations)
            assertEquals(VAPID, push.vapid)
            assertEquals(BackgroundPushState(PushStatus.Registering, false), viewModel.state.value.backgroundPush)
            assertEquals(1, calls.permissionAsks)
        }

    @Test
    fun `an endpoint the platform hands over at once is not overwritten by registering`() =
        runViewModelTest {
            val push = FakeBackgroundPush(arriving = PushSubscription("https://fcm.googleapis.com/fcm/send/up1", "p256", "auth"))
            val viewModel = viewModel(push = push)

            viewModel.setBackgroundPush(true)

            assertEquals(BackgroundPushState(PushStatus.Unsent, false), viewModel.state.value.backgroundPush)
        }

    @Test
    fun `without Google services the row offers the own connection and turning it on makes its keys`() =
        runViewModelTest {
            val push = FakeBackgroundPush(transport = PushTransport.OwnConnection)
            val viewModel = viewModel(push = push)
            assertEquals(BackgroundPushState(PushStatus.Off, true), viewModel.state.value.backgroundPush)

            viewModel.setBackgroundPush(true)

            assertEquals(1, push.ownRegistrations)
            assertEquals(0, push.registrations)
            assertEquals(BackgroundPushState(PushStatus.Unsent, true), viewModel.state.value.backgroundPush)
        }

    @Test
    fun `an own connection the server took reads as a connection, not as Google`() =
        runViewModelTest {
            val preferences = DevicePreferences(InMemoryPreferencesStore(), CoroutineScope(Dispatchers.Main), DeviceSettings())
            val viewModel = viewModel(push = FakeBackgroundPush(transport = PushTransport.OwnConnection), preferences = preferences)
            val own = PushSubscription(OWN_CONNECTION, "p256", "auth")

            preferences.setPushSubscription(own)
            preferences.recordPushAnswer(own, PushStatus.Sent, "tok")

            assertEquals(BackgroundPushState(PushStatus.Sent, true), viewModel.state.value.backgroundPush)
        }

    @Test
    fun `a server that pushes nothing is reported and nothing registers`() =
        runViewModelTest {
            val push = FakeBackgroundPush()
            val viewModel = viewModel(push = push, vapidKey = { null })

            viewModel.setBackgroundPush(true)

            assertEquals(0, push.registrations)
            assertEquals(BackgroundPushState(PushStatus.OffOnServer, false), viewModel.state.value.backgroundPush)
        }

    @Test
    fun `a server that cannot be reached leaves the row failed`() =
        runViewModelTest {
            val push = FakeBackgroundPush()
            val viewModel = viewModel(push = push, vapidKey = { throw JmapException(JmapError.Server(502)) })

            viewModel.setBackgroundPush(true)

            assertEquals(0, push.registrations)
            assertEquals(BackgroundPushState(PushStatus.Failed, false), viewModel.state.value.backgroundPush)
        }

    @Test
    fun `turning background notifications off unregisters and waits to tell the server`() =
        runViewModelTest {
            val push = FakeBackgroundPush()
            val viewModel = viewModel(push = push)
            viewModel.setBackgroundPush(true)

            viewModel.setBackgroundPush(false)

            assertEquals(1, push.unregistrations)
            assertEquals(BackgroundPushState(PushStatus.Removing, false), viewModel.state.value.backgroundPush)
        }

    @Test
    fun `the row follows the server's answer for the endpoint`() =
        runViewModelTest {
            val preferences = DevicePreferences(InMemoryPreferencesStore(), CoroutineScope(Dispatchers.Main), DeviceSettings())
            val viewModel = viewModel(push = FakeBackgroundPush(), preferences = preferences)
            val subscription = PushSubscription("https://fcm.googleapis.com/fcm/send/up1", "p256", "auth")

            preferences.setPushSubscription(subscription)
            assertEquals(BackgroundPushState(PushStatus.Unsent, false), viewModel.state.value.backgroundPush)

            val answers =
                mapOf(
                    PushStatus.Sent to BackgroundPushState(PushStatus.Sent, false),
                    PushStatus.OffOnServer to BackgroundPushState(PushStatus.OffOnServer, false),
                    PushStatus.NotApproved to BackgroundPushState(PushStatus.NotApproved, false),
                    PushStatus.Refused to BackgroundPushState(PushStatus.Refused, false),
                )
            for ((status, line) in answers) {
                preferences.recordPushAnswer(subscription, status)
                assertEquals(line, viewModel.state.value.backgroundPush)
                assertTrue(line.on)
            }
        }

    @Test
    fun `turning on what is already on registers nothing more`() =
        runViewModelTest {
            val push = FakeBackgroundPush()
            val viewModel = viewModel(push = push)
            viewModel.setBackgroundPush(true)

            viewModel.setBackgroundPush(true)

            assertEquals(1, push.registrations)
        }
}
