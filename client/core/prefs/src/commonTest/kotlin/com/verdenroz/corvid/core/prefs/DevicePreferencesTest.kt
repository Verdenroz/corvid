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
import kotlin.test.assertNull
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
    fun `the newest digest seen starts as none and is published once set`() =
        runTest {
            val prefs = preferences()
            runCurrent()
            assertNull(prefs.settings.value.digestSeen)

            prefs.setDigestSeen("d7")
            runCurrent()

            assertEquals("d7", prefs.settings.value.digestSeen)
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
    fun `new mail notifications start on and stay off once turned off`() =
        runTest {
            val prefs = preferences()
            runCurrent()
            assertEquals(true, prefs.settings.value.newMailNotifications)

            prefs.setNewMailNotifications(false)
            runCurrent()

            assertEquals(false, prefs.settings.value.newMailNotifications)
        }

    @Test
    fun `a file written before new mail notifications existed reads them as on`() {
        val preferences = preferencesOf(stringPreferencesKey("theme") to "Dark", longPreferencesKey("notices_seen") to 3L)

        assertEquals(DeviceSettings(ThemeChoice.Dark, noticesSeen = 3, newMailNotifications = true), decode(preferences))
    }

    @Test
    fun `a signed-in account is remembered with its email and leaves the other choices alone`() =
        runTest {
            val prefs = preferences()
            prefs.setTheme(ThemeChoice.Dark)

            prefs.rememberAccount(RememberedAccount("https://mail.example.com:4443", "you@example.com"))
            runCurrent()

            assertEquals(
                DeviceSettings(
                    ThemeChoice.Dark,
                    account = RememberedAccount("https://mail.example.com:4443", "you@example.com"),
                ),
                prefs.settings.value,
            )
        }

    @Test
    fun `an account remembered without an email replaces the last one and drops its email`() =
        runTest {
            val prefs = preferences()
            prefs.rememberAccount(RememberedAccount("https://mail.example.com", "you@example.com"))

            prefs.rememberAccount(RememberedAccount("https://other.example.org"))
            runCurrent()

            assertEquals(RememberedAccount("https://other.example.org"), prefs.settings.value.account)
        }

    @Test
    fun `an account stored by a build that kept an identity provider still opens without it`() {
        val stored =
            preferencesOf(
                stringPreferencesKey("account_server") to "https://mail.example.com",
                stringPreferencesKey("account_issuer") to "https://idp.example.com",
                stringPreferencesKey("account_email") to "you@example.com",
            )

        assertEquals(RememberedAccount("https://mail.example.com", "you@example.com"), decode(stored).account)
    }

    @Test
    fun `no server stored is no account to offer`() {
        assertNull(decode(preferencesOf(stringPreferencesKey("account_email") to "you@example.com")).account)
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

    @Test
    fun `a new endpoint waits to be sent and the server's answer is recorded against it`() =
        runTest {
            val prefs = preferences()
            val subscription = PushSubscription("https://push.example.com/up1", "key", "secret")

            prefs.setPushSubscription(subscription)
            runCurrent()
            assertEquals(PushSettings(PushStatus.Unsent, subscription), prefs.settings.value.push)

            prefs.recordPushAnswer(subscription, PushStatus.Sent)
            runCurrent()

            assertEquals(PushSettings(PushStatus.Sent, subscription), prefs.settings.value.push)
        }

    @Test
    fun `an own connection keeps the token the server issued until the connection is turned off`() =
        runTest {
            val prefs = preferences()
            val subscription = PushSubscription(OWN_CONNECTION, "key", "secret")
            prefs.setPushSubscription(subscription)

            prefs.recordPushAnswer(subscription, PushStatus.Sent, streamToken = "tok")
            runCurrent()
            assertEquals(PushSettings(PushStatus.Sent, subscription, "tok"), prefs.settings.value.push)

            prefs.removePush()
            runCurrent()
            assertEquals(PushSettings(PushStatus.Removing), prefs.settings.value.push)
        }

    @Test
    fun `the last event read from the own connection is remembered`() =
        runTest {
            val prefs = preferences()
            assertNull(prefs.streamCursor())

            prefs.setStreamCursor("1790000000123")

            assertEquals("1790000000123", prefs.streamCursor())
        }

    @Test
    fun `an answer for an endpoint the connector has since replaced is dropped`() =
        runTest {
            val prefs = preferences()
            val old = PushSubscription("https://push.example.com/up1", "key", "secret")
            val new = PushSubscription("https://push.example.com/up2", "key2", "secret2")
            prefs.setPushSubscription(old)
            prefs.setPushSubscription(new)

            prefs.recordPushAnswer(old, PushStatus.Sent)
            runCurrent()

            assertEquals(PushSettings(PushStatus.Unsent, new), prefs.settings.value.push)
        }

    @Test
    fun `removing forgets the endpoint and a finished removal turns push off`() =
        runTest {
            val prefs = preferences()
            prefs.setPushSubscription(PushSubscription("https://push.example.com/up1", "key", "secret"))

            prefs.removePush()
            runCurrent()
            assertEquals(PushSettings(PushStatus.Removing), prefs.settings.value.push)

            prefs.finishPushRemoval()
            runCurrent()
            assertEquals(PushSettings(), prefs.settings.value.push)
        }

    @Test
    fun `a removal finishing after push was turned back on leaves the new registration alone`() =
        runTest {
            val prefs = preferences()
            prefs.removePush()
            prefs.setPushRegistering()

            prefs.finishPushRemoval()
            runCurrent()

            assertEquals(PushSettings(PushStatus.Registering), prefs.settings.value.push)
        }
}
