package com.verdenroz.corvid.core.keyring

import com.verdenroz.corvid.core.jmap.AuthLatch
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.jmap.createJmapHttpClient
import com.verdenroz.corvid.core.testing.FakeJmapServer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

private const val BASE_URL = "http://127.0.0.1:9999"

class DeviceApprovalRepositoryTest {
    private val fake = FakeJmapServer()
    private val latch = AuthLatch()

    @Test
    fun `opening a request returns its id, code and expiry`() =
        runTest {
            val opened = assertIs<OpenOutcome.Opened>(repository().open("laptop-1"))

            assertEquals(6, opened.request.code.length)
            assertEquals(Instant.parse("2030-01-01T00:00:00Z"), opened.request.expiresAt)
            assertEquals(listOf("laptop-1"), fake.devices.opened)
        }

    @Test
    fun `too many open requests is reported, not thrown`() =
        runTest {
            fake.devices.full = true

            assertIs<OpenOutcome.TooManyOpen>(repository().open("laptop-1"))
            assertFalse(latch.tripped.value)
        }

    @Test
    fun `a server with device approval off is reported, not thrown`() =
        runTest {
            fake.devices.off = true

            assertIs<OpenOutcome.NotServed>(repository().open("laptop-1"))
            assertFalse(latch.tripped.value)
        }

    @Test
    fun `a 404 from a request that never reached the sentinel is a failure, not approval off`() =
        runTest {
            fake.devices.unrouted = true

            assertFailsWith<JmapException> { repository().open("laptop-1") }
            assertFalse(latch.tripped.value)
        }

    @Test
    fun `a request reads off once the server switches device approval off`() =
        runTest {
            val repository = repository()
            val request = opened(repository)
            fake.devices.off = true

            assertEquals(RequestStatus.Off, repository.status(request.id))
        }

    @Test
    fun `a request whose status never reaches the sentinel is a failure, not expired`() =
        runTest {
            val repository = repository()
            val request = opened(repository)
            fake.devices.unrouted = true

            assertFailsWith<JmapException> { repository.status(request.id) }
        }

    @Test
    fun `a request reads pending, then approved once a master approves it`() =
        runTest {
            val repository = repository()
            val request = opened(repository)

            assertEquals(RequestStatus.Pending, repository.status(request.id))
            fake.devices.approveElsewhere(request.id)
            assertEquals(RequestStatus.Approved, repository.status(request.id))
        }

    @Test
    fun `an unknown or expired request reads as expired`() =
        runTest {
            val repository = repository()
            val request = opened(repository)
            fake.devices.expire(request.id)

            assertEquals(RequestStatus.Expired, repository.status(request.id))
            assertEquals(RequestStatus.Expired, repository.status("nope"))
        }

    @Test
    fun `an approval carries the master token and the daily bearer, and makes the key`() =
        runTest {
            val repository = repository(bearer = "daily")
            val request = opened(repository)

            val outcome = repository.approve(request.id, request.code, "master-token")

            assertIs<ApproveOutcome.Approved>(outcome)
            val seen = fake.devices.approvals.single()
            assertEquals("master-token", seen.master)
            assertEquals("Bearer daily", seen.authorization)
            assertIs<DeviceKeyLookup.Found>(DeviceKeyRepository(client(bearer = "daily")).fetch("laptop-1"))
        }

    @Test
    fun `a master token the server does not accept is a refusal and leaves the session signed in`() =
        runTest {
            val repository = repository()
            val request = opened(repository)

            assertIs<ApproveOutcome.NotAccepted>(repository.approve(request.id, request.code, "stale"))
            assertFalse(latch.tripped.value)
        }

    @Test
    fun `a daily bearer the gate refuses signs the session out instead of reading as a master refusal`() =
        runTest {
            val repository = repository()
            val request = opened(repository)
            fake.devices.dailyRejected = true

            assertFailsWith<JmapException> { repository.approve(request.id, request.code, "master-token") }
            assertTrue(latch.tripped.value)
        }

    @Test
    fun `a wrong code is refused and makes no key`() =
        runTest {
            val repository = repository()
            val request = opened(repository)
            val wrong = if (request.code == "000000") "000001" else "000000"

            assertIs<ApproveOutcome.Refused>(repository.approve(request.id, wrong, "master-token"))
            assertNull(fake.devices.keyBlobIds.firstOrNull())
        }

    @Test
    fun `approving a request that is gone reads as gone`() =
        runTest {
            val repository = repository()
            val request = opened(repository)
            fake.devices.expire(request.id)

            assertIs<ApproveOutcome.Gone>(repository.approve(request.id, request.code, "master-token"))
        }

    @Test
    fun `a server that cannot make the key reads as upstream failure`() =
        runTest {
            val repository = repository()
            val request = opened(repository)
            fake.devices.upstreamFails = true

            assertIs<ApproveOutcome.UpstreamFailed>(repository.approve(request.id, request.code, "master-token"))
        }

    private suspend fun opened(repository: DeviceApprovalRepository) = assertIs<OpenOutcome.Opened>(repository.open("laptop-1")).request

    private suspend fun repository(bearer: String? = null) = DeviceApprovalRepository(client(bearer))

    private suspend fun client(bearer: String?) =
        JmapClient.connect(createJmapHttpClient(fake.engine), latch, BASE_URL, bearerToken = { bearer })
}
