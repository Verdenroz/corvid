package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.jmap.AuthLatch
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.createJmapHttpClient
import com.verdenroz.corvid.core.testing.FakeEmail
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.withSendableAccount
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

private const val ADA = "ada@analytical.test"
private const val ADA_PASS = "mx; dmarc=pass header.from=analytical.test"

private fun results(vararg values: String) = mapOf("Authentication-Results" to values.toList())

private fun rewrite(vararg values: String) = mapOf("X-Corvid-Rewrite" to values.toList())

private fun sent(
    id: String,
    to: List<String> = emptyList(),
    cc: List<String> = emptyList(),
    bcc: List<String> = emptyList(),
) = FakeEmail(id, id, setOf("sent"), "s", "verde@example.test", "2024-01-01T00:00:00Z", to = to, cc = cc, bcc = bcc)

private fun from(
    id: String,
    address: String,
    at: String = "2024-01-01T00:00:00Z",
    headers: Map<String, List<String>> = emptyMap(),
) = FakeEmail(id, id, setOf("inbox"), "s", address, at, headers = headers)

private suspend fun senders(fake: FakeJmapServer): SenderRepository {
    val client = JmapClient.connect(createJmapHttpClient(fake.engine), AuthLatch(), "http://127.0.0.1:9999")
    return SenderRepository(client, MailboxRepository(client))
}

class SenderRepositoryTest {
    @Test
    fun `a count includes only messages from exactly that address`() =
        runTest {
            val fake = FakeJmapServer().withSendableAccount()
            fake.emails += from("a1", ADA)
            fake.emails += from("a2", "ADA@analytical.test")
            fake.emails += from("lookalike", "lada@analytical.test")
            fake.emails += from("suffixed", "ada@analytical.test.evil")

            assertEquals(2, senders(fake).stats(ADA).received)
        }

    @Test
    fun `first seen is the oldest exact match, not the oldest text match`() =
        runTest {
            val fake = FakeJmapServer().withSendableAccount()
            fake.emails += from("lookalike", "lada@analytical.test", at = "2019-01-01T00:00:00Z")
            fake.emails += from("first", ADA, at = "2025-03-02T10:00:00Z")
            fake.emails += from("later", ADA, at = "2026-01-01T00:00:00Z")

            assertEquals(Instant.parse("2025-03-02T10:00:00Z"), senders(fake).stats(ADA).firstSeen)
        }

    @Test
    fun `signed counts only the server's own verdict`() =
        runTest {
            val fake = FakeJmapServer().withSendableAccount()
            fake.emails += from("ok", ADA, headers = results(ADA_PASS))
            fake.emails += from("forged", ADA, headers = results("mx; dmarc=none", ADA_PASS))

            val stats = senders(fake).stats(ADA)

            assertEquals(1, stats.signed)
            assertEquals(2, stats.received)
        }

    @Test
    fun `trackers stripped sum only the messages with one readable header, and say how many those were`() =
        runTest {
            val fake = FakeJmapServer().withSendableAccount()
            fake.emails += from("r1", ADA, headers = rewrite("stripped=4 proxied=0 hosts=1 trackers=2"))
            fake.emails += from("r2", ADA, headers = rewrite("none"))
            fake.emails += from("forged", ADA, headers = rewrite("stripped=9 proxied=0 hosts=0 trackers=9", "none"))
            fake.emails += from("unmarked", ADA)

            val stats = senders(fake).stats(ADA)

            assertEquals(4, stats.trackersStripped)
            assertEquals(2, stats.marked)
        }

    @Test
    fun `sent counts messages in sent addressed to them in any recipient field`() =
        runTest {
            val fake = FakeJmapServer().withSendableAccount()
            fake.emails += sent("to", to = listOf(ADA))
            fake.emails += sent("cc", cc = listOf(ADA))
            fake.emails += sent("bcc", bcc = listOf(ADA))
            fake.emails += sent("lookalike", to = listOf("lada@analytical.test"))
            fake.emails += FakeEmail("inbox", "inbox", setOf("inbox"), "s", "bob@b.test", "2024-01-05T00:00:00Z", to = listOf(ADA))

            assertEquals(3, senders(fake).stats(ADA).sent)
        }

    @Test
    fun `a sender with more messages than one page is counted in full`() =
        runTest {
            val fake = FakeJmapServer().withSendableAccount()
            repeat(600) { fake.emails += from("m$it", ADA, at = Instant.fromEpochSeconds(it.toLong()).toString()) }

            val stats = senders(fake).stats(ADA)

            assertEquals(600, stats.received)
            assertEquals(Instant.fromEpochSeconds(0), stats.firstSeen)
        }

    @Test
    fun `a mailbox that changes between pages is counted again from the start`() =
        runTest {
            val fake = FakeJmapServer().withSendableAccount()
            repeat(300) { fake.emails += from("m$it", ADA, at = Instant.fromEpochSeconds(it.toLong()).toString()) }
            var queries = 0
            fake.beforeEmailQuery = {
                queries++
                if (queries == 2) {
                    fake.emails += from("arrived", ADA, at = Instant.fromEpochSeconds(-1).toString(), headers = results(ADA_PASS))
                    fake.queryState = "qs2"
                }
            }

            val stats = senders(fake).stats(ADA)

            assertEquals(301, stats.received)
            assertEquals(1, stats.signed)
            assertEquals(Instant.fromEpochSeconds(-1), stats.firstSeen)
        }
}
