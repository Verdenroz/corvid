package com.verdenroz.corvid.app

import com.verdenroz.corvid.core.keyring.PushEndpointRepository
import com.verdenroz.corvid.core.prefs.DevicePreferences
import com.verdenroz.corvid.core.prefs.DeviceSettings
import com.verdenroz.corvid.core.prefs.OWN_CONNECTION
import com.verdenroz.corvid.core.prefs.PushSettings
import com.verdenroz.corvid.core.prefs.PushStatus
import com.verdenroz.corvid.core.prefs.PushSubscription
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.InMemoryPreferencesStore
import com.verdenroz.corvid.core.testing.client
import io.ktor.http.HttpMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

private val SUBSCRIPTION = PushSubscription("https://fcm.googleapis.com/fcm/send/abc", "p256", "auth")

/** The fake answers on another thread, so the test waits on real time for what it caused. */
private suspend fun until(condition: () -> Boolean) {
    withContext(Dispatchers.Default) {
        withTimeout(5.seconds) { while (!condition()) delay(5) }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun TestScope.preferences(): DevicePreferences =
    DevicePreferences(InMemoryPreferencesStore(), backgroundScope, DeviceSettings()).also { runCurrent() }

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun TestScope.follow(
    fake: FakeJmapServer,
    preferences: DevicePreferences,
) {
    val sync = PushSync(PushEndpointRepository(fake.client()), preferences, "phone")
    backgroundScope.launch { sync.run() }
    runCurrent()
}

@OptIn(ExperimentalCoroutinesApi::class)
class PushSyncTest {
    private val fake = FakeJmapServer().apply { devices.enroll("phone") }

    @Test
    fun `an endpoint held when the app unlocks is sent and recorded as sent`() =
        runTest {
            val preferences = preferences()
            preferences.setPushSubscription(SUBSCRIPTION)

            follow(fake, preferences)
            until { preferences.settings.value.push.status == PushStatus.Sent }

            assertEquals(SUBSCRIPTION.url, fake.devices.pushEndpoints["phone"]?.get("endpoint")?.jsonPrimitive?.content)
        }

    @Test
    fun `an endpoint the server already took is sent again on unlock`() =
        runTest {
            val preferences = preferences()
            preferences.setPushSubscription(SUBSCRIPTION)
            preferences.recordPushAnswer(SUBSCRIPTION, PushStatus.Sent)

            follow(fake, preferences)
            until { fake.devices.pushCalls.isNotEmpty() }

            assertEquals(listOf(HttpMethod.Put to "phone"), fake.devices.pushCalls)
        }

    @Test
    fun `an own connection is sent once and the token the server issues is recorded`() =
        runTest {
            val preferences = preferences()
            val own = PushSubscription(OWN_CONNECTION, "p256", "auth")
            preferences.setPushSubscription(own)

            follow(fake, preferences)
            until { preferences.settings.value.push.status == PushStatus.Sent }

            assertEquals(PushSettings(PushStatus.Sent, own, fake.devices.streamToken), preferences.settings.value.push)
        }

    @Test
    fun `an own connection the server already took is not registered again on unlock`() =
        runTest {
            val preferences = preferences()
            val own = PushSubscription(OWN_CONNECTION, "p256", "auth")
            preferences.setPushSubscription(own)
            preferences.recordPushAnswer(own, PushStatus.Sent, "tok")

            follow(fake, preferences)

            assertEquals(emptyList(), fake.devices.pushCalls)
            assertEquals("tok", preferences.settings.value.push.streamToken)
        }

    @Test
    fun `an endpoint that arrives while unlocked is sent at once`() =
        runTest {
            val preferences = preferences()
            follow(fake, preferences)
            assertEquals(emptyList(), fake.devices.pushCalls)

            preferences.setPushSubscription(SUBSCRIPTION)
            until { preferences.settings.value.push.status == PushStatus.Sent }

            assertEquals(listOf(HttpMethod.Put to "phone"), fake.devices.pushCalls)
        }

    @Test
    fun `turning push off removes the endpoint from the server and then reads as off`() =
        runTest {
            val preferences = preferences()
            preferences.setPushSubscription(SUBSCRIPTION)
            follow(fake, preferences)
            until { preferences.settings.value.push.status == PushStatus.Sent }

            preferences.removePush()
            until { preferences.settings.value.push == PushSettings() }

            assertEquals(emptyMap(), fake.devices.pushEndpoints)
        }

    @Test
    fun `a server without push is recorded as off there, keeping the endpoint`() =
        runTest {
            fake.devices.pushOff = true
            val preferences = preferences()
            preferences.setPushSubscription(SUBSCRIPTION)

            follow(fake, preferences)
            until { preferences.settings.value.push.status != PushStatus.Unsent }

            assertEquals(PushSettings(PushStatus.OffOnServer, SUBSCRIPTION), preferences.settings.value.push)
        }

    @Test
    fun `a device the server does not know is recorded as not approved`() =
        runTest {
            fake.devices.revoke("phone")
            val preferences = preferences()
            preferences.setPushSubscription(SUBSCRIPTION)

            follow(fake, preferences)
            until { preferences.settings.value.push.status != PushStatus.Unsent }

            assertEquals(PushStatus.NotApproved, preferences.settings.value.push.status)
        }

    @Test
    fun `an unreachable server leaves the endpoint waiting to be sent`() =
        runTest {
            val preferences = preferences()
            preferences.setPushSubscription(SUBSCRIPTION)
            val sync = PushSync(PushEndpointRepository(fake.client()), preferences, "phone")
            fake.unreachable = true

            backgroundScope.launch { sync.run() }
            until { fake.requestCount > 1 }
            runCurrent()

            assertEquals(PushSettings(PushStatus.Unsent, SUBSCRIPTION), preferences.settings.value.push)
        }
}
