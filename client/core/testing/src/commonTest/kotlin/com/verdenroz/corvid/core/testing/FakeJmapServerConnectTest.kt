package com.verdenroz.corvid.core.testing

import com.verdenroz.corvid.core.jmap.AuthLatch
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.JmapError
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.jmap.JmapSessionException
import com.verdenroz.corvid.core.jmap.createJmapHttpClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

class FakeJmapServerConnectTest {
    @Test
    fun `a session is discovered and rebased onto the dialled host`() =
        runTest {
            val fake = FakeJmapServer()
            val client = createJmapHttpClient(fake.engine)

            val session = JmapClient.connect(client, AuthLatch(), "http://127.0.0.1:9999").session

            assertEquals("http://127.0.0.1:9999/jmap/api/", session.apiUrl)
            assertEquals(
                "http://127.0.0.1:9999/jmap/download/{accountId}/{blobId}/{name}?accept={type}",
                session.downloadUrl,
            )
            assertEquals("u1", session.accountId)
        }

    @Test
    fun `a 401 is sent once and the next call never reaches the network`() =
        runTest {
            val fake = FakeJmapServer()
            fake.unauthorized = true
            val client = createJmapHttpClient(fake.engine)
            val latch = AuthLatch()

            assertFailsWith<JmapException> { JmapClient.connect(client, latch, "http://127.0.0.1:9999") }
            assertEquals(1, fake.requestCount)

            assertFailsWith<JmapException> { JmapClient.connect(client, latch, "http://127.0.0.1:9999") }
            assertEquals(1, fake.requestCount)
        }

    @Test
    fun `an unreachable session lookup is not mistaken for a 401`() =
        runTest {
            val fake = FakeJmapServer()
            fake.unreachable = true
            val client = createJmapHttpClient(fake.engine)
            val latch = AuthLatch()

            val thrown =
                assertFailsWith<JmapException> {
                    JmapClient.connect(client, latch, "http://127.0.0.1:9999")
                }

            assertIs<JmapError.Unreachable>(thrown.error)
            assertFalse(latch.tripped.value)
        }

    @Test
    fun `a bad certificate is a tls failure, not an unreachable retry`() =
        runTest {
            val fake = FakeJmapServer()
            fake.tlsFailure = true
            val client = createJmapHttpClient(fake.engine)
            val latch = AuthLatch()

            val thrown =
                assertFailsWith<JmapException> {
                    JmapClient.connect(client, latch, "http://127.0.0.1:9999")
                }

            assertIs<JmapError.TlsFailure>(thrown.error)
        }

    @Test
    fun `a 429 carries its Retry-After through as rate limiting`() =
        runTest {
            val fake = FakeJmapServer()
            fake.rateLimitedFor = 30
            val client = createJmapHttpClient(fake.engine)

            val thrown =
                assertFailsWith<JmapException> {
                    JmapClient.connect(client, AuthLatch(), "http://127.0.0.1:9999")
                }

            val error = assertIs<JmapError.RateLimited>(thrown.error)
            assertEquals(30, error.retryAfterSeconds)
        }

    @Test
    fun `an anonymous session with no mail account is unauthenticated, not silently accepted`() =
        runTest {
            val fake = FakeJmapServer()
            fake.unauthenticated = true
            val client = createJmapHttpClient(fake.engine)

            val thrown =
                assertFailsWith<JmapException> {
                    JmapClient.connect(client, AuthLatch(), "http://127.0.0.1:9999")
                }

            assertIs<JmapError.Unauthorized>(thrown.error)
        }

    @Test
    fun `a session missing filenode fails naming the missing capability`() =
        runTest {
            val fake = FakeJmapServer()
            fake.missingCapability = "urn:ietf:params:jmap:filenode"
            val client = createJmapHttpClient(fake.engine)

            val thrown =
                assertFailsWith<JmapSessionException> {
                    JmapClient.connect(client, AuthLatch(), "http://127.0.0.1:9999")
                }

            assertEquals(true, thrown.message?.contains("filenode"))
        }

    @Test
    fun `a session missing its api url is refused rather than rebased from empty`() =
        runTest {
            val fake = FakeJmapServer()
            fake.missingUrl = "apiUrl"
            val client = createJmapHttpClient(fake.engine)

            assertFailsWith<JmapSessionException> {
                JmapClient.connect(client, AuthLatch(), "http://127.0.0.1:9999")
            }
        }
}
