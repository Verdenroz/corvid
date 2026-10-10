package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.testing.FakeEmail
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.client
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private fun message(
    id: String,
    thread: String,
    at: String,
    mailbox: String = "inbox",
    keywords: Set<String> = setOf("\$seen"),
    body: String? = null,
) = FakeEmail(id, thread, setOf(mailbox), "subject $thread", "$id@example.test", at, keywords = keywords, body = body)

private suspend fun repository(fake: FakeJmapServer) = MailboxRepository(fake.client())

class ThreadsTest {
    @Test
    fun `a thread is listed once, by its newest message in the mailbox`() =
        runTest {
            val fake = FakeJmapServer()
            fake.emails += message("a1", "a", "2024-01-01T00:00:00Z")
            fake.emails += message("a2", "a", "2024-01-03T00:00:00Z")
            fake.emails += message("b1", "b", "2024-01-02T00:00:00Z")

            val page = assertIs<EmailPage.Found>(repository(fake).page(MailFilter.InMailbox("inbox"), null))

            assertEquals(listOf("a2", "b1"), page.conversations.map { it.latest.id })
            assertEquals(listOf(listOf("a1", "a2"), listOf("b1")), page.conversations.map { it.emailIds })
        }

    @Test
    fun `a conversation whose email lacks a preview gets an empty one`() =
        runTest {
            val fake = FakeJmapServer()
            fake.emails += message("a1", "a", "2024-01-01T00:00:00Z").copy(preview = null)

            val page = assertIs<EmailPage.Found>(repository(fake).page(MailFilter.InMailbox("inbox"), null))

            assertEquals("", page.conversations.single().latest.preview)
        }

    @Test
    fun `a thread counts its messages filed elsewhere`() =
        runTest {
            val fake = FakeJmapServer()
            fake.emails += message("in", "a", "2024-01-01T00:00:00Z")
            fake.emails += message("out", "a", "2024-01-02T00:00:00Z", mailbox = "sent")

            val page = assertIs<EmailPage.Found>(repository(fake).page(MailFilter.InMailbox("inbox"), null))

            assertEquals(2, page.conversations.single().messageCount)
            assertEquals("in", page.conversations.single().latest.id)
        }

    @Test
    fun `a thread is unread when any message in it is, not only the newest`() =
        runTest {
            val fake = FakeJmapServer()
            fake.emails += message("old", "a", "2024-01-01T00:00:00Z", keywords = emptySet())
            fake.emails += message("new", "a", "2024-01-02T00:00:00Z")
            fake.emails += message("read", "b", "2024-01-03T00:00:00Z")

            val page = assertIs<EmailPage.Found>(repository(fake).page(MailFilter.InMailbox("inbox"), null))

            assertEquals(listOf("read", "new"), page.conversations.map { it.latest.id })
            assertEquals(listOf(false, true), page.conversations.map { it.unread })
            assertEquals(setOf("old"), page.conversations.last().unreadIds)
        }

    @Test
    fun `a page of conversations is one request`() =
        runTest {
            val fake = FakeJmapServer()
            fake.emails += message("a1", "a", "2024-01-01T00:00:00Z")
            fake.emails += message("a2", "a", "2024-01-02T00:00:00Z")
            val repository = repository(fake)
            repository.page(MailFilter.InMailbox("inbox"), null)

            val before = fake.requestCount
            repository.page(MailFilter.InMailbox("inbox"), null)

            assertEquals(before + 1, fake.requestCount)
        }

    @Test
    fun `paging by anchor never repeats a thread`() =
        runTest {
            val fake = FakeJmapServer()
            repeat(120) { i ->
                val at = "2024-01-01T00:${(i / 60).toString().padStart(2, '0')}:${(i % 60).toString().padStart(2, '0')}Z"
                fake.emails += message("m$i", "t${i / 2}", at)
            }
            val repository = repository(fake)

            val first = assertIs<EmailPage.Found>(repository.page(MailFilter.InMailbox("inbox"), null))
            val second = assertIs<EmailPage.Found>(repository.page(MailFilter.InMailbox("inbox"), first.conversations.last().latest.id))

            assertEquals(50, first.conversations.size)
            assertEquals(10, second.conversations.size)
            assertEquals(60, (first.conversations + second.conversations).map { it.emailIds }.distinct().size)
        }

    @Test
    fun `a search lists each matching message on its own`() =
        runTest {
            val fake = FakeJmapServer()
            fake.emails += message("a1", "a", "2024-01-01T00:00:00Z")
            fake.emails += message("a2", "a", "2024-01-02T00:00:00Z")

            val page = assertIs<EmailPage.Found>(repository(fake).page(MailFilter.Search("subject"), null))

            assertEquals(listOf("a2", "a1"), page.conversations.map { it.latest.id })
            assertEquals(listOf(1, 1), page.conversations.map { it.messageCount })
        }

    @Test
    fun `starred threads are listed by their newest starred message`() =
        runTest {
            val fake = FakeJmapServer()
            val starred = setOf("\$seen", "\$flagged")
            fake.emails += message("a1", "a", "2024-01-01T00:00:00Z", keywords = starred)
            fake.emails += message("a2", "a", "2024-01-02T00:00:00Z")

            val page = assertIs<EmailPage.Found>(repository(fake).page(MailFilter.Starred, null))

            assertEquals("a1", page.conversations.single().latest.id)
            assertEquals(2, page.conversations.single().messageCount)
        }

    @Test
    fun `a thread opens oldest message first with every body`() =
        runTest {
            val fake = FakeJmapServer()
            fake.emails += message("a2", "a", "2024-01-02T00:00:00Z", body = "second")
            fake.emails += message("b1", "b", "2024-01-01T00:00:00Z", body = "other")
            fake.emails += message("a1", "a", "2024-01-01T00:00:00Z", mailbox = "sent", body = "first")

            val lookup = assertIs<ThreadLookup.Found>(repository(fake).thread("a2"))

            assertEquals(listOf("a1", "a2"), lookup.messages.map { it.id })
            assertEquals(listOf("first", "second"), lookup.messages.map { it.body })
        }

    @Test
    fun `a forged message in a thread is suspect without making its neighbours so`() =
        runTest {
            val fake = FakeJmapServer()
            val forged = mapOf("Authentication-Results" to listOf("mx; dkim=pass header.d=evil.test; dmarc=fail header.from=bank.test"))
            fake.emails += message("honest", "a", "2024-01-01T00:00:00Z", body = "hi")
            fake.emails += message("forged", "a", "2024-01-02T00:00:00Z", body = "pay").copy(from = "service@bank.test", headers = forged)
            fake.emails += message("reply", "a", "2024-01-03T00:00:00Z", body = "ok")

            val lookup = assertIs<ThreadLookup.Found>(repository(fake).thread("reply"))

            assertEquals(listOf(false, true, false), lookup.messages.map { it.provenance.suspect })
        }

    @Test
    fun `each message of a thread carries its HTML part`() =
        runTest {
            val fake = FakeJmapServer()
            fake.emails += message("a1", "a", "2024-01-01T00:00:00Z", body = "<p>hi</p>").copy(bodyType = "text/html")
            fake.emails += message("a2", "a", "2024-01-02T00:00:00Z", body = "plain")

            val lookup = assertIs<ThreadLookup.Found>(repository(fake).thread("a2"))

            assertEquals(listOf("<p>hi</p>", null), lookup.messages.map { it.html?.html })
        }

    @Test
    fun `a thread is one request however many messages it holds`() =
        runTest {
            val fake = FakeJmapServer()
            repeat(5) { fake.emails += message("a$it", "a", "2024-01-0${it + 1}T00:00:00Z", body = "b") }
            val repository = repository(fake)
            repository.page(MailFilter.InMailbox("inbox"), null)

            val before = fake.requestCount
            repository.thread("a4")

            assertEquals(before + 1, fake.requestCount)
        }

    @Test
    fun `a thread whose message is gone is reported not found`() =
        runTest {
            assertIs<ThreadLookup.NotFound>(repository(FakeJmapServer()).thread("gone"))
        }

    @Test
    fun `a seen patch on an older message clears it from the conversation's unread set`() =
        runTest {
            val fake = FakeJmapServer()
            fake.emails += message("old", "a", "2024-01-01T00:00:00Z", keywords = emptySet())
            fake.emails += message("new", "a", "2024-01-02T00:00:00Z", keywords = emptySet())
            val conversation = assertIs<EmailPage.Found>(repository(fake).page(MailFilter.InMailbox("inbox"), null)).conversations.single()

            val patched = KeywordPatch("old", mapOf("\$seen" to true)).applyTo(conversation)

            assertEquals(setOf("new"), patched.unreadIds)
            assertEquals(conversation.latest, patched.latest)
        }

    @Test
    fun `a patch for another thread leaves the conversation as it was`() =
        runTest {
            val fake = FakeJmapServer()
            fake.emails += message("a", "a", "2024-01-01T00:00:00Z", keywords = emptySet())
            val conversation = assertIs<EmailPage.Found>(repository(fake).page(MailFilter.InMailbox("inbox"), null)).conversations.single()

            assertEquals(conversation, KeywordPatch("elsewhere", mapOf("\$seen" to true)).applyTo(conversation))
        }

    @Test
    fun `several messages are marked in one request and only the accepted ones are announced`() =
        runTest {
            val fake = FakeJmapServer()
            fake.emails += message("a1", "a", "2024-01-01T00:00:00Z", keywords = emptySet())
            fake.emails += message("a2", "a", "2024-01-02T00:00:00Z", keywords = emptySet())
            val repository = repository(fake)
            val patches = mutableListOf<KeywordPatch>()
            val collecting = launch(start = CoroutineStart.UNDISPATCHED) { repository.keywordPatches.collect { patches += it } }
            val before = fake.requestCount

            val refused = repository.updateKeywords(listOf("a1", "gone", "a2"), mapOf("\$seen" to true))
            yield()
            collecting.cancel()

            assertEquals(setOf("gone"), refused.keys)
            assertEquals(1, fake.requestCount - before)
            assertEquals(listOf("a1", "a2"), patches.map { it.emailId })
        }

    @Test
    fun `a message carries the server's own preview`() =
        runTest {
            val fake = FakeJmapServer()
            fake.emails += message("a", "t", "2024-01-01T00:00:00Z", body = "first words and more").copy(preview = "first words")

            val thread = assertIs<ThreadLookup.Found>(repository(fake).thread("a"))

            assertEquals("first words", thread.messages.single().preview)
        }
}
