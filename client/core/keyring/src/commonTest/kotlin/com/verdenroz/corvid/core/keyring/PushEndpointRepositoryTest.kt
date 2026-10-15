package com.verdenroz.corvid.core.keyring

import com.verdenroz.corvid.core.jmap.AuthLatch
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.jmap.createJmapHttpClient
import com.verdenroz.corvid.core.prefs.PushStatus
import com.verdenroz.corvid.core.testing.FakeJmapServer
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val BASE_URL = "http://127.0.0.1:9999"
private const val ENDPOINT = "https://fcm.googleapis.com/fcm/send/abc"

class PushEndpointRepositoryTest {
    private val fake = FakeJmapServer()
    private val latch = AuthLatch()

    @Test
    fun `an approved device's endpoint and keys are stored under its name`() =
        runTest {
            fake.devices.enroll("phone")

            assertEquals(PushStatus.Sent, repository().store("phone", ENDPOINT, "p256", "auth").status)

            val expected =
                buildJsonObject {
                    put("endpoint", ENDPOINT)
                    put("p256dh", "p256")
                    put("auth", "auth")
                }
            assertEquals(expected, fake.devices.pushEndpoints["phone"])
        }

    @Test
    fun `the own connection's registration answers with the token to read it with`() =
        runTest {
            fake.devices.enroll("phone")

            val result = repository().store("phone", "corvid-stream", "p256", "auth")

            assertEquals(PushResult(PushStatus.Sent, fake.devices.streamToken), result)
        }

    @Test
    fun `a device the server never approved is reported, not thrown`() =
        runTest {
            assertEquals(PushStatus.NotApproved, repository().store("phone", ENDPOINT, "p256", "auth").status)
            assertFalse(latch.tripped.value)
        }

    @Test
    fun `an endpoint outside the server's push host is refused`() =
        runTest {
            fake.devices.enroll("phone")

            assertEquals(PushStatus.Refused, repository().store("phone", "https://ntfy.sh/abc", "p256", "auth").status)
            assertTrue(fake.devices.pushEndpoints.isEmpty())
        }

    @Test
    fun `a server without push or without device routes is off, for storing and removing alike`() =
        runTest {
            fake.devices.enroll("phone")
            val repository = repository()
            fake.devices.pushOff = true
            assertEquals(PushStatus.OffOnServer, repository.store("phone", ENDPOINT, "p256", "auth").status)
            repository.remove("phone")

            fake.devices.pushOff = false
            fake.devices.off = true
            assertEquals(PushStatus.OffOnServer, repository.store("phone", ENDPOINT, "p256", "auth").status)
        }

    @Test
    fun `removing forgets the stored endpoint and succeeds again when none is left`() =
        runTest {
            fake.devices.enroll("phone")
            val repository = repository()
            repository.store("phone", ENDPOINT, "p256", "auth")

            repository.remove("phone")
            repository.remove("phone")

            assertTrue(fake.devices.pushEndpoints.isEmpty())
            assertEquals(
                listOf(HttpMethod.Put to "phone", HttpMethod.Delete to "phone", HttpMethod.Delete to "phone"),
                fake.devices.pushCalls,
            )
        }

    @Test
    fun `the server's VAPID key is read, and a server that pushes nothing has none`() =
        runTest {
            val repository = repository()
            assertEquals(fake.devices.vapidKey, repository.vapidKey())

            fake.devices.pushOff = true
            assertNull(repository.vapidKey())
        }

    @Test
    fun `a forward-auth refusal trips the latch`() =
        runTest {
            val repository = repository()
            fake.devices.dailyRejected = true

            assertFailsWith<JmapException> { repository.store("phone", ENDPOINT, "p256", "auth") }
            assertTrue(latch.tripped.value)
        }

    private suspend fun repository() =
        PushEndpointRepository(JmapClient.connect(createJmapHttpClient(fake.engine), latch, BASE_URL, bearerToken = { null }))

    @Test
    fun `asking for the digest push registers the kind for the device`() =
        runTest {
            fake.devices.enroll("phone")

            repository().askForDigestPush("phone")

            assertEquals(listOf("digest"), fake.devices.pushKinds["phone"])
        }

    @Test
    fun `a server without the kinds route is asked once`() =
        runTest {
            fake.devices.enroll("phone")
            fake.devices.kindsUnsupported = true
            val repository = repository()

            repository.askForDigestPush("phone")
            repository.askForDigestPush("phone")

            assertEquals(1, fake.devices.kindsCalls)
        }
}
