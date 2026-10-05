package com.verdenroz.corvid.app

import com.verdenroz.corvid.core.data.NoticeRepository
import com.verdenroz.corvid.core.model.NoticeKind
import com.verdenroz.corvid.core.prefs.DevicePreferences
import com.verdenroz.corvid.core.prefs.DeviceSettings
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.FakeNotice
import com.verdenroz.corvid.core.testing.InMemoryPreferencesStore
import com.verdenroz.corvid.core.testing.KeyedTextResolver
import com.verdenroz.corvid.core.testing.client
import com.verdenroz.corvid.core.testing.serveNotices
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private class Rig(
    val fake: FakeJmapServer,
    val preferences: DevicePreferences,
    val notices: Notices,
    val foreground: Channel<Unit>,
    val posted: MutableList<Pair<String, String>>,
)

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun TestScope.rig(
    fake: FakeJmapServer,
    seen: Long = 0,
    pollEvery: Duration = 60.seconds,
): Rig {
    val preferences = DevicePreferences(InMemoryPreferencesStore(), backgroundScope, DeviceSettings())
    preferences.setNoticesSeen(seen)
    runCurrent()
    val posted = mutableListOf<Pair<String, String>>()
    val foreground = Channel<Unit>(Channel.CONFLATED)
    val notices =
        Notices(NoticeRepository(fake.client()), preferences, { title, text -> posted += title to text }, KeyedTextResolver, pollEvery)
    backgroundScope.launch { notices.run(foreground.receiveAsFlow()) }
    runCurrent()
    return Rig(fake, preferences, notices, foreground, posted)
}

/** The fake answers on another thread, so the test waits on real time for what it caused. */
private suspend fun until(condition: () -> Boolean) {
    withContext(Dispatchers.Default) {
        withTimeout(5.seconds) { while (!condition()) delay(5) }
    }
}

private val BAN = FakeNotice(3, "ban", title = "Address banned", detail = "203.0.113.9 was banned")

@OptIn(ExperimentalCoroutinesApi::class)
class NoticesTest {
    @Test
    fun `several unseen notices become one banner on the newest, with a count`() =
        runTest {
            val rig = rig(FakeJmapServer().apply { notices.serveNotices(listOf(FakeNotice(1), FakeNotice(2), BAN)) })

            until { rig.notices.banner.value != null }

            val banner = rig.notices.banner.value
            assertEquals(3, banner?.newest?.id)
            assertEquals(NoticeKind.Ban, banner?.newest?.kind)
            assertEquals(3, banner?.count)
        }

    @Test
    fun `the banner for several reads as the newest title and how many more`() =
        runTest {
            val rig = rig(FakeJmapServer().apply { notices.serveNotices(listOf(FakeNotice(1), BAN)) })

            until { rig.notices.banner.value != null }

            assertEquals("notices_more[Address banned, 1]", KeyedTextResolver.resolve(rig.notices.banner.value!!.line))
        }

    @Test
    fun `one notification is posted for a batch, carrying the server's own words and nothing from mail`() =
        runTest {
            val rig = rig(FakeJmapServer().apply { notices.serveNotices(listOf(BAN)) })

            until { rig.posted.isNotEmpty() }

            assertEquals(listOf("Address banned" to "203.0.113.9 was banned"), rig.posted)
        }

    @Test
    fun `a batch of several posts one notification titled with the count`() =
        runTest {
            val rig = rig(FakeJmapServer().apply { notices.serveNotices(listOf(FakeNotice(1), FakeNotice(2), BAN)) })

            until { rig.posted.isNotEmpty() }

            assertEquals(listOf("notices_more[Address banned, 2]" to "203.0.113.9 was banned"), rig.posted)
        }

    @Test
    fun `the newest id shown is remembered and the next poll asks only after it`() =
        runTest {
            val rig = rig(FakeJmapServer().apply { notices.serveNotices(listOf(FakeNotice(1), BAN)) })
            until { rig.notices.banner.value != null }

            advanceTimeBy(61.seconds)
            until { rig.fake.notices.reads.size >= 2 }

            assertEquals(3, rig.preferences.settings.value.noticesSeen)
            assertEquals(listOf("after=0", "after=3"), rig.fake.notices.reads.take(2))
        }

    @Test
    fun `a device that has seen up to an id is told only of newer notices`() =
        runTest {
            val rig = rig(FakeJmapServer().apply { notices.serveNotices(listOf(FakeNotice(1), FakeNotice(2), BAN)) }, seen = 2)

            until { rig.notices.banner.value != null }

            assertEquals(1, rig.notices.banner.value?.count)
            assertEquals("after=2", rig.fake.notices.reads.first())
        }

    @Test
    fun `a notice that arrives while the banner is up adds to its count and notifies again`() =
        runTest {
            val rig = rig(FakeJmapServer().apply { notices.serveNotices(listOf(FakeNotice(1))) })
            until { rig.notices.banner.value != null }

            rig.fake.notices.serveNotices(listOf(FakeNotice(1), BAN))
            advanceTimeBy(61.seconds)
            until { rig.notices.banner.value?.count == 2 }

            assertEquals(3, rig.notices.banner.value?.newest?.id)
            assertEquals(2, rig.posted.size)
        }

    @Test
    fun `a dismissed banner stays gone and nothing is posted again`() =
        runTest {
            val rig = rig(FakeJmapServer().apply { notices.serveNotices(listOf(BAN)) })
            until { rig.notices.banner.value != null }

            rig.notices.dismiss()
            advanceTimeBy(61.seconds)
            until { rig.fake.notices.reads.size >= 2 }

            assertNull(rig.notices.banner.value)
            assertEquals(1, rig.posted.size)
        }

    @Test
    fun `a server without the feed stays silent and is asked again`() =
        runTest {
            val rig = rig(FakeJmapServer())
            until { rig.fake.requestCount > 0 }
            advanceTimeBy(61.seconds)
            until { rig.fake.requestCount > 2 }

            rig.fake.notices.serveNotices(listOf(BAN))
            advanceTimeBy(61.seconds)
            until { rig.notices.banner.value != null }

            assertEquals(1, rig.posted.size)
        }

    @Test
    fun `a failing server is ignored and the next poll recovers`() =
        runTest {
            val fake = FakeJmapServer().apply { notices.serveNotices(listOf(BAN)) }
            fake.notices.broken = true
            val rig = rig(fake)
            until { rig.fake.requestCount > 1 }
            assertNull(rig.notices.banner.value)

            fake.notices.broken = false
            advanceTimeBy(61.seconds)
            until { rig.notices.banner.value != null }

            assertEquals(1, rig.posted.size)
        }

    @Test
    fun `returning to the foreground polls at once`() =
        runTest {
            val rig = rig(FakeJmapServer().apply { notices.serveNotices(emptyList()) }, pollEvery = Duration.INFINITE)
            until { rig.fake.notices.reads.size == 1 && rig.fake.requestCount > 1 }

            rig.fake.notices.serveNotices(listOf(BAN))
            rig.foreground.send(Unit)
            until { rig.notices.banner.value != null }

            assertEquals(listOf("after=0", "after=0"), rig.fake.notices.reads)
        }

    @Test
    fun `returning to the foreground during a poll still triggers another right after it`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val fake = FakeJmapServer().apply { notices.serveNotices(emptyList()) }
            fake.notices.gate = gate
            val rig = rig(fake, pollEvery = Duration.INFINITE)
            until { fake.notices.arrivals == 1 }

            fake.notices.serveNotices(listOf(BAN))
            rig.foreground.send(Unit)
            gate.complete(Unit)
            until { rig.notices.banner.value != null }

            assertEquals(listOf("after=0", "after=0"), fake.notices.reads)
        }

    @Test
    fun `several returns during a poll make one more poll, not several`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val fake = FakeJmapServer().apply { notices.serveNotices(listOf(BAN)) }
            fake.notices.gate = gate
            val rig = rig(fake, pollEvery = Duration.INFINITE)
            until { fake.notices.arrivals == 1 }

            repeat(3) { rig.foreground.send(Unit) }
            gate.complete(Unit)
            until { fake.notices.arrivals == 2 }
            withContext(Dispatchers.Default) { delay(200) }

            assertEquals(2, fake.notices.arrivals)
        }
}
