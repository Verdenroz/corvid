package com.verdenroz.corvid.app

import com.verdenroz.corvid.core.data.DigestRepository
import com.verdenroz.corvid.core.prefs.DevicePreferences
import com.verdenroz.corvid.core.prefs.DeviceSettings
import com.verdenroz.corvid.core.testing.FakeDigest
import com.verdenroz.corvid.core.testing.FakeDigestItem
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.InMemoryPreferencesStore
import com.verdenroz.corvid.core.testing.client
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

private class DigestRig(
    val preferences: DevicePreferences,
    val newDigest: NewDigest,
    val triggers: Channel<Unit>,
)

private fun mail(count: Int) = FakeDigest("d1", groups = listOf("Work" to List(count) { FakeDigestItem(emailId = "e$it") }))

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun TestScope.digestRig(
    fake: FakeJmapServer,
    preferences: DevicePreferences = DevicePreferences(InMemoryPreferencesStore(), backgroundScope, DeviceSettings()),
): DigestRig {
    runCurrent()
    val triggers = Channel<Unit>(Channel.CONFLATED)
    val newDigest = NewDigest(DigestRepository(fake.client()), preferences)
    backgroundScope.launch { newDigest.run(triggers.receiveAsFlow()) }
    return DigestRig(preferences, newDigest, triggers)
}

/** The fake answers on another thread, so the test waits on real time for what it caused. */
private suspend fun untilDigest(condition: () -> Boolean) {
    withContext(Dispatchers.Default) {
        withTimeout(5.seconds) { while (!condition()) delay(5) }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class NewDigestTest {
    @Test
    fun `a digest not seen yet becomes a banner with its message count`() =
        runTest {
            val rig = digestRig(FakeJmapServer().apply { digests.serve(mail(3)) })

            untilDigest { rig.newDigest.banner.value != null }

            assertEquals(3, rig.newDigest.banner.value?.count)
            assertEquals("d1", rig.newDigest.banner.value?.id)
        }

    @Test
    fun `opening the digest clears the banner and keeps it away`() =
        runTest {
            val rig = digestRig(FakeJmapServer().apply { digests.serve(mail(1)) })
            untilDigest { rig.newDigest.banner.value != null }

            rig.newDigest.opened("d1")
            runCurrent()

            assertNull(rig.newDigest.banner.value)
            assertEquals("d1", rig.preferences.settings.value.digestSeen)
            rig.triggers.send(Unit)
            runCurrent()
            assertNull(rig.newDigest.banner.value)
        }

    @Test
    fun `dismissing the banner clears it and keeps it away`() =
        runTest {
            val rig = digestRig(FakeJmapServer().apply { digests.serve(mail(1)) })
            untilDigest { rig.newDigest.banner.value != null }

            rig.newDigest.dismiss()
            runCurrent()
            rig.triggers.send(Unit)
            runCurrent()

            assertNull(rig.newDigest.banner.value)
            assertEquals("d1", rig.preferences.settings.value.digestSeen)
        }

    @Test
    fun `opening the latest digest without an id clears the banner too`() =
        runTest {
            val rig = digestRig(FakeJmapServer().apply { digests.serve(mail(1)) })
            untilDigest { rig.newDigest.banner.value != null }

            rig.newDigest.opened(null)
            runCurrent()

            assertNull(rig.newDigest.banner.value)
        }

    @Test
    fun `a digest already seen on this device is not offered again`() =
        runTest {
            val preferences = DevicePreferences(InMemoryPreferencesStore(), backgroundScope, DeviceSettings())
            preferences.setDigestSeen("d1")
            val fake = FakeJmapServer().apply { digests.serve(mail(2)) }
            val rig = digestRig(fake, preferences)

            untilDigest { fake.digests.reads > 0 }
            runCurrent()

            assertNull(rig.newDigest.banner.value)
        }

    @Test
    fun `a digest with no mail is never offered`() =
        runTest {
            val fake = FakeJmapServer().apply { digests.serve(FakeDigest("d1", groups = listOf("Work" to emptyList()))) }
            val rig = digestRig(fake)

            untilDigest { fake.digests.reads > 0 }
            runCurrent()

            assertNull(rig.newDigest.banner.value)
        }

    @Test
    fun `a digest opened before the check finished is not offered afterwards`() =
        runTest {
            val fake = FakeJmapServer().apply { digests.serve(mail(1)) }
            val preferences = DevicePreferences(InMemoryPreferencesStore(), backgroundScope, DeviceSettings())
            val newDigest = NewDigest(DigestRepository(fake.client()), preferences)
            newDigest.opened("d1")

            backgroundScope.launch { newDigest.run(Channel<Unit>().receiveAsFlow()) }
            untilDigest { fake.digests.reads > 0 }
            runCurrent()

            assertNull(newDigest.banner.value)
            assertEquals("d1", preferences.settings.value.digestSeen)
        }

    @Test
    fun `a later trigger finds a digest that arrived after the first check`() =
        runTest {
            val fake = FakeJmapServer()
            val rig = digestRig(fake)
            untilDigest { fake.digests.reads > 0 }
            runCurrent()
            assertNull(rig.newDigest.banner.value)

            fake.digests.serve(mail(2))
            rig.triggers.send(Unit)
            untilDigest { rig.newDigest.banner.value != null }

            assertEquals(2, rig.newDigest.banner.value?.count)
        }
}
