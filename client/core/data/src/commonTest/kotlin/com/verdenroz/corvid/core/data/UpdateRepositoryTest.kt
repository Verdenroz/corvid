package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.jmap.JmapError
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.model.UpdateReport
import com.verdenroz.corvid.core.model.UpdateResult
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.FakeUpdate
import com.verdenroz.corvid.core.testing.actions
import com.verdenroz.corvid.core.testing.client
import com.verdenroz.corvid.core.testing.serveUpdates
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

private suspend fun repository(
    fake: FakeJmapServer,
    token: String? = null,
) = UpdateRepository(fake.client(token))

class UpdateRepositoryTest {
    @Test
    fun `a report is read field for field`() =
        runTest {
            val fake =
                FakeJmapServer().apply {
                    update.serveUpdates(
                        FakeUpdate(
                            running = "0.2.0",
                            latest = "0.2.1",
                            checkedAt = "2026-10-04T09:30:00Z",
                            result = "rolledBack",
                            detail = "health check failed",
                            auto = false,
                            busy = true,
                        ),
                    )
                }

            assertEquals(
                UpdateReport(
                    running = "0.2.0",
                    latest = "0.2.1",
                    checkedAt = Instant.parse("2026-10-04T09:30:00Z"),
                    result = UpdateResult.RolledBack,
                    detail = "health check failed",
                    auto = false,
                    busy = true,
                ),
                repository(fake).status(),
            )
        }

    @Test
    fun `null latest, checked time and detail stay null`() =
        runTest {
            val report = repository(FakeJmapServer().apply { update.serveUpdates(FakeUpdate()) }).status()

            assertEquals(Triple(null, null, null), Triple(report?.latest, report?.checkedAt, report?.detail))
            assertFalse(report?.updateAvailable ?: true)
        }

    @Test
    fun `an update is available only when latest differs from running`() =
        runTest {
            val newer = repository(FakeJmapServer().apply { update.serveUpdates(FakeUpdate(running = "0.2.0", latest = "0.2.1")) }).status()
            val same = repository(FakeJmapServer().apply { update.serveUpdates(FakeUpdate(running = "0.2.1", latest = "0.2.1")) }).status()

            assertEquals(listOf(true, false), listOf(newer?.updateAvailable, same?.updateAvailable))
        }

    @Test
    fun `a server without the endpoint reports no status instead of failing`() =
        runTest {
            assertNull(repository(FakeJmapServer()).status())
        }

    @Test
    fun `a result the client has never seen is refused loudly`() =
        runTest {
            val fake = FakeJmapServer().apply { update.serveUpdates(FakeUpdate(result = "exploded")) }

            val failure = assertFailsWith<JmapException> { repository(fake).status() }

            assertIs<JmapError.Request>(failure.error)
        }

    @Test
    fun `a server error is a failure`() =
        runTest {
            val fake =
                FakeJmapServer().apply {
                    update.serveUpdates(FakeUpdate())
                    update.broken = true
                }

            assertEquals(JmapError.Server(500), assertFailsWith<JmapException> { repository(fake).status() }.error)
        }

    @Test
    fun `check and apply post their action`() =
        runTest {
            val fake = FakeJmapServer().apply { update.serveUpdates(FakeUpdate()) }
            val repository = repository(fake)

            repository.check()
            repository.apply()

            assertEquals(listOf("check", "apply"), fake.update.actions)
        }

    @Test
    fun `requests carry the same bearer token the JMAP calls use`() =
        runTest {
            val fake = FakeJmapServer().apply { update.serveUpdates(FakeUpdate()) }

            repository(fake, token = "tok").status()

            assertEquals("Bearer tok", fake.update.authorization)
        }
}
