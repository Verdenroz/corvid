package com.verdenroz.corvid.core.auth

import com.verdenroz.corvid.core.jmap.AuthLatch
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.createJmapHttpClient
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.FakePasskey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Instant

private const val BASE_URL = "http://127.0.0.1:9999"

class PasskeyRepositoryTest {
    private val fake = FakeJmapServer()
    private val latch = AuthLatch()

    private suspend fun repository() =
        PasskeyRepository(JmapClient.connect(createJmapHttpClient(fake.engine), latch, BASE_URL, bearerToken = { "daily" }))

    @Test
    fun `the list names whether each passkey approves devices, whether it syncs and when it was made`() =
        runTest {
            fake.passkeys.passkeys += FakePasskey("a", backupEligible = true, createdAt = "2030-01-03T10:00:00Z")
            fake.passkeys.passkeys += FakePasskey("b", approves = true)

            assertEquals(
                PasskeyListing.Listed(
                    listOf(
                        Passkey("a", false, true, Instant.parse("2030-01-03T10:00:00Z")),
                        Passkey("b", true, false, Instant.parse("2030-01-01T00:00:00Z")),
                    ),
                ),
                repository().list(),
            )
        }

    @Test
    fun `a server with passkeys off lists nothing and says so`() =
        runTest {
            fake.passkeys.off = true

            assertEquals(PasskeyListing.NotServed, repository().list())
            assertFalse(latch.tripped.value)
        }

    @Test
    fun `a code is minted with the admin token on one header and says when it expires`() =
        runTest {
            val outcome = repository().mintCode(approves = false, "master-token")

            assertEquals(CodeOutcome.Minted("ABCD-EFGH-IJKL-MNOP", Instant.parse("2030-01-02T00:00:00Z")), outcome)
            assertEquals(listOf(false), fake.passkeys.minted)
            assertEquals(listOf<String?>("master-token"), fake.passkeys.masters)
        }

    @Test
    fun `an admin token the server does not accept mints nothing and does not trip the latch`() =
        runTest {
            val outcome = repository().mintCode(approves = false, "stale")

            assertEquals(CodeOutcome.NotAccepted, outcome)
            assertEquals(emptyList(), fake.passkeys.minted)
            assertFalse(latch.tripped.value)
        }

    @Test
    fun `a server with passkeys off is reported when minting`() =
        runTest {
            fake.passkeys.off = true

            assertEquals(CodeOutcome.NotServed, repository().mintCode(approves = false, "master-token"))
        }

    @Test
    fun `a passkey is removed with the admin token`() =
        runTest {
            fake.passkeys.passkeys += FakePasskey("a")
            fake.passkeys.passkeys += FakePasskey("b")

            assertEquals(RemoveOutcome.Removed, repository().remove("a", "master-token"))

            assertEquals(listOf("b"), fake.passkeys.passkeys.map { it.id })
        }

    @Test
    fun `the last passkey is kept`() =
        runTest {
            fake.passkeys.passkeys += FakePasskey("a", approves = true)

            assertEquals(RemoveOutcome.Last, repository().remove("a", "master-token"))

            assertEquals(1, fake.passkeys.passkeys.size)
        }

    @Test
    fun `the last approver is kept while plain passkeys remain`() =
        runTest {
            fake.passkeys.passkeys += FakePasskey("a", approves = true)
            fake.passkeys.passkeys += FakePasskey("b")

            assertEquals(RemoveOutcome.Last, repository().remove("a", "master-token"))

            assertEquals(2, fake.passkeys.passkeys.size)
        }

    @Test
    fun `a passkey the server does not hold is gone`() =
        runTest {
            assertEquals(RemoveOutcome.Gone, repository().remove("nope", "master-token"))
        }

    @Test
    fun `a removal with an admin token the server does not accept removes nothing`() =
        runTest {
            fake.passkeys.passkeys += FakePasskey("a")
            fake.passkeys.passkeys += FakePasskey("b")

            assertEquals(RemoveOutcome.NotAccepted, repository().remove("a", "stale"))

            assertEquals(2, fake.passkeys.passkeys.size)
            assertFalse(latch.tripped.value)
        }
}
