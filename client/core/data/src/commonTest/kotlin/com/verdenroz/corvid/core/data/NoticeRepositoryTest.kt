package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.jmap.JmapError
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.model.Notice
import com.verdenroz.corvid.core.model.NoticeKind
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.FakeNotice
import com.verdenroz.corvid.core.testing.client
import com.verdenroz.corvid.core.testing.serveNotices
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

private suspend fun repository(
    fake: FakeJmapServer,
    token: String? = null,
) = NoticeRepository(fake.client(token))

class NoticeRepositoryTest {
    @Test
    fun `a notice is read field for field`() =
        runTest {
            val fake =
                FakeJmapServer().apply {
                    notices.serveNotices(listOf(FakeNotice(7, "update", "2026-10-04T09:30:00Z", "Update failed", "pull failed")))
                }

            assertEquals(
                listOf(Notice(7, NoticeKind.Update, Instant.parse("2026-10-04T09:30:00Z"), "Update failed", "pull failed")),
                repository(fake).after(),
            )
        }

    @Test
    fun `every kind the server writes is understood`() =
        runTest {
            val kinds = listOf("signIn", "tooManyAttempts", "ban", "update")
            val fake = FakeJmapServer().apply { notices.serveNotices(kinds.mapIndexed { index, kind -> FakeNotice(index + 1L, kind) }) }

            assertEquals(NoticeKind.entries, repository(fake).after()?.map { it.kind })
        }

    @Test
    fun `asking after an id returns only newer notices, oldest first, and says so on the wire`() =
        runTest {
            val fake = FakeJmapServer().apply { notices.serveNotices(listOf(FakeNotice(1), FakeNotice(2), FakeNotice(3))) }

            assertEquals(listOf(2L, 3L), repository(fake).after(1)?.map { it.id })
            assertEquals(listOf("after=1"), fake.notices.reads)
        }

    @Test
    fun `asking for everything sends no after`() =
        runTest {
            val fake = FakeJmapServer().apply { notices.serveNotices(listOf(FakeNotice(1))) }

            repository(fake).after()

            assertEquals(listOf(""), fake.notices.reads)
        }

    @Test
    fun `an empty feed is an empty list, not an absent one`() =
        runTest {
            assertEquals(emptyList(), repository(FakeJmapServer().apply { notices.serveNotices(emptyList()) }).after())
        }

    @Test
    fun `a server without the endpoint reports no feed instead of failing`() =
        runTest {
            assertNull(repository(FakeJmapServer()).after())
        }

    @Test
    fun `a kind the client has never seen is refused loudly`() =
        runTest {
            val fake = FakeJmapServer().apply { notices.serveNotices(listOf(FakeNotice(1, kind = "meteor"))) }

            val failure = assertFailsWith<JmapException> { repository(fake).after() }

            assertIs<JmapError.Request>(failure.error)
        }

    @Test
    fun `a server error is a failure`() =
        runTest {
            val fake = FakeJmapServer().apply { notices.serveNotices(listOf(FakeNotice(1))) }
            fake.notices.broken = true

            assertFailsWith<JmapException> { repository(fake).after() }
        }

    @Test
    fun `the bearer token travels with the request`() =
        runTest {
            val fake = FakeJmapServer().apply { notices.serveNotices(emptyList()) }

            repository(fake, token = "tok").after()

            assertEquals("Bearer tok", fake.notices.authorization)
        }
}
