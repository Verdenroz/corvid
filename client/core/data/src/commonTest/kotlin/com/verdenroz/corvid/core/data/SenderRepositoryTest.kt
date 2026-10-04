package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.jmap.AuthLatch
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.createJmapHttpClient
import com.verdenroz.corvid.core.testing.FakeEmail
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.withSendableAccount
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

            assertEquals(2, assertNotNull(senders(fake).counts(ADA)).received)
        }

    @Test
    fun `first seen is the oldest exact match, not the oldest text match`() =
        runTest {
            val fake = FakeJmapServer().withSendableAccount()
            fake.emails += from("lookalike", "lada@analytical.test", at = "2019-01-01T00:00:00Z")
            fake.emails += from("first", ADA, at = "2025-03-02T10:00:00Z")
            fake.emails += from("later", ADA, at = "2026-01-01T00:00:00Z")

            assertEquals(Instant.parse("2025-03-02T10:00:00Z"), assertNotNull(senders(fake).counts(ADA)).firstSeen)
        }

    @Test
    fun `signed counts only the server's own verdict`() =
        runTest {
            val fake = FakeJmapServer().withSendableAccount()
            fake.emails += from("ok", ADA, headers = results(ADA_PASS))
            fake.emails += from("forged", ADA, headers = results("mx; dmarc=none", ADA_PASS))

            val window = assertNotNull(senders(fake).newest(ADA)?.let(::senderWindow))

            assertEquals(1, window.signed)
            assertEquals(2, window.size)
        }

    @Test
    fun `trackers stripped sum only the messages with one readable header, and say how many those were`() =
        runTest {
            val fake = FakeJmapServer().withSendableAccount()
            fake.emails += from("r1", ADA, headers = rewrite("stripped=4 proxied=0 hosts=1 trackers=2"))
            fake.emails += from("r2", ADA, headers = rewrite("none"))
            fake.emails += from("forged", ADA, headers = rewrite("stripped=9 proxied=0 hosts=0 trackers=9", "none"))
            fake.emails += from("unmarked", ADA)

            val window = assertNotNull(senders(fake).newest(ADA)?.let(::senderWindow))

            assertEquals(4, window.trackersStripped)
            assertEquals(2, window.marked)
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

            assertEquals(3, assertNotNull(senders(fake).counts(ADA)).sent)
        }

    @Test
    fun `a sender with more messages than one page is counted in full`() =
        runTest {
            val fake = FakeJmapServer().withSendableAccount()
            repeat(1200) { fake.emails += from("m$it", ADA, at = Instant.fromEpochSeconds(it.toLong()).toString()) }

            val counts = assertNotNull(senders(fake).counts(ADA))

            assertEquals(1200, counts.received)
            assertEquals(Instant.fromEpochSeconds(0), counts.firstSeen)
        }

    @Test
    fun `a mailbox that changes between pages is counted again from the start`() =
        runTest {
            val fake = FakeJmapServer().withSendableAccount()
            repeat(700) { fake.emails += from("m$it", ADA, at = Instant.fromEpochSeconds(it.toLong()).toString()) }
            var queries = 0
            fake.beforeEmailQuery = {
                queries++
                if (queries == 2) {
                    fake.emails += from("arrived", ADA, at = Instant.fromEpochSeconds(-1).toString(), headers = results(ADA_PASS))
                    fake.queryState = "qs2"
                }
            }

            val counts = assertNotNull(senders(fake).counts(ADA))

            assertEquals(701, counts.received)
            assertEquals(Instant.fromEpochSeconds(-1), counts.firstSeen)
        }

    @Test
    fun `the window reads only the newest two hundred exact matches`() =
        runTest {
            val fake = FakeJmapServer().withSendableAccount()
            fake.emails += from("oldest", ADA, at = Instant.fromEpochSeconds(0).toString(), headers = results(ADA_PASS))
            repeat(200) { fake.emails += from("m$it", ADA, at = Instant.fromEpochSeconds(10L + it).toString()) }
            repeat(50) {
                val at = Instant.fromEpochSeconds(1000L + it).toString()
                fake.emails += from("l$it", "lada@analytical.test", at = at, headers = results(ADA_PASS))
            }

            val window = assertNotNull(senders(fake).newest(ADA)?.let(::senderWindow))

            assertEquals(SENDER_WINDOW, window.size)
            assertEquals(0, window.signed)
        }

    @Test
    fun `a cancelled count sends nothing after the page in flight`() =
        runTest {
            val fake = FakeJmapServer().withSendableAccount()
            repeat(1200) { fake.emails += from("m$it", ADA, at = Instant.fromEpochSeconds(it.toLong()).toString()) }
            val repository = senders(fake)
            var queries = 0
            lateinit var job: Job
            fake.beforeEmailQuery = {
                queries++
                job.cancel()
            }

            job = launch { repository.counts(ADA) }
            job.join()

            assertEquals(1, queries)
        }

    @Test
    fun `the window stops reading once it holds two hundred matches`() =
        runTest {
            val fake = FakeJmapServer().withSendableAccount()
            repeat(1200) { fake.emails += from("m$it", ADA, at = Instant.fromEpochSeconds(it.toLong()).toString()) }
            var queries = 0
            fake.beforeEmailQuery = { queries++ }

            senders(fake).newest(ADA)?.let(::senderWindow)

            assertEquals(1, queries)
        }

    @Test
    fun `a mailbox that changes on every page gives up after three recounts and shows no number`() =
        runTest {
            val fake = FakeJmapServer().withSendableAccount()
            repeat(1200) { fake.emails += from("m$it", ADA, at = Instant.fromEpochSeconds(it.toLong()).toString()) }
            var queries = 0
            fake.beforeEmailQuery = {
                queries++
                fake.queryState = "qs$queries"
            }

            assertNull(senders(fake).counts(ADA))
            assertEquals(8, queries)
        }
}
