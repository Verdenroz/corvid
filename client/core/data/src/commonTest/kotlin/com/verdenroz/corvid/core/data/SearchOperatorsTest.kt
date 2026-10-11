package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.model.CorvidKeywords
import com.verdenroz.corvid.core.testing.FakeEmail
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.FakeMailbox
import com.verdenroz.corvid.core.testing.client
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Instant

private val NOW = Instant.parse("2026-10-10T12:00:00Z")

private object FixedClock : Clock {
    override fun now() = NOW
}

private fun server() =
    FakeJmapServer().apply {
        mailboxes += FakeMailbox("inbox", "Inbox", INBOX)
        mailboxes += FakeMailbox("sent", "Sent", "sent")
        mailboxes += FakeMailbox("trash", "Trash", TRASH)
        mailboxes += FakeMailbox("junk", "Junk", JUNK)
        emails +=
            email("invoice", INBOX, "Your invoice", "billing@shop.test", "2026-10-09")
                .copy(hasAttachment = true, cc = listOf("accounts@shop.test"))
        emails +=
            email("lunch", INBOX, "Lunch on Friday", "ada@friends.test", "2026-06-01")
                .copy(keywords = setOf(CorvidKeywords.SEEN, CorvidKeywords.FLAGGED), preview = "the usual place")
        emails += email("reply", "sent", "Re: Lunch on Friday", "me@home.test", "2026-06-02").copy(to = listOf("ada@friends.test"))
        emails += email("binned", TRASH, "Old invoice", "billing@shop.test", "2025-01-01")
        emails += email("spam", JUNK, "Invoice overdue!!", "scam@bad.test", "2026-10-08")
    }

private fun email(
    id: String,
    mailbox: String,
    subject: String,
    from: String,
    day: String,
) = FakeEmail(id, id, setOf(mailbox), subject, from, "${day}T08:00:00Z")

private suspend fun search(
    text: String,
    fake: FakeJmapServer = server(),
): EmailPage.Found {
    val repository = MailboxRepository(fake.client(), FixedClock, TimeZone.UTC)
    return assertIs<EmailPage.Found>(repository.page(MailFilter.Search(text), null))
}

private suspend fun ids(text: String) = search(text).conversations.map { it.latest.id }

class SearchOperatorsTest {
    @Test
    fun `a search leaves out trash and junk unless told to look anywhere`() =
        runTest {
            assertEquals(listOf("invoice"), ids("invoice"))
            assertEquals(listOf("invoice", "spam", "binned"), ids("invoice in:anywhere"))
        }

    @Test
    fun `naming trash searches only trash`() =
        runTest {
            assertEquals(listOf("binned"), ids("in:trash invoice"))
        }

    @Test
    fun `naming a mailbox the account lacks finds nothing and asks the server nothing`() =
        runTest {
            var queries = 0
            val fake = server().apply { beforeEmailQuery = { queries++ } }
            val page = search("in:archive invoice", fake)

            assertEquals(emptyList(), page.conversations)
            assertEquals(0, page.total)
            assertEquals(0, queries)
        }

    @Test
    fun `the first page of a search carries the total the server counted`() =
        runTest {
            assertEquals(2, search("lunch in:anywhere").total)
        }

    @Test
    fun `a mailbox page asks for no total`() =
        runTest {
            val repository = MailboxRepository(server().client(), FixedClock, TimeZone.UTC)

            assertNull(assertIs<EmailPage.Found>(repository.page(MailFilter.InMailbox("inbox"), null)).total)
        }

    @Test
    fun `sender, recipient, cc and subject operators each match their own field`() =
        runTest {
            assertEquals(listOf("lunch"), ids("from:ada"))
            assertEquals(listOf("reply"), ids("to:ada"))
            assertEquals(listOf("invoice"), ids("cc:accounts"))
            assertEquals(listOf("reply"), ids("subject:re:"))
        }

    @Test
    fun `attachments, read state and stars filter by what the server knows of the message`() =
        runTest {
            assertEquals(listOf("invoice"), ids("has:attachment"))
            assertEquals(listOf("invoice", "reply"), ids("is:unread"))
            assertEquals(listOf("lunch"), ids("is:starred"))
        }

    @Test
    fun `dates and ages bound when the message arrived`() =
        runTest {
            assertEquals(listOf("reply", "lunch"), ids("before:2026-10-01"))
            assertEquals(listOf("invoice"), ids("after:2026/10/01"))
            assertEquals(listOf("invoice"), ids("newer_than:7d"))
            assertEquals(listOf("reply", "lunch"), ids("older_than:1m"))
        }

    @Test
    fun `a negated word drops the messages that hold it`() =
        runTest {
            assertEquals(listOf("invoice", "reply"), ids("-usual"))
        }

    @Test
    fun `a quoted phrase must appear whole`() =
        runTest {
            assertEquals(listOf("lunch"), ids("\"usual place\""))
            assertEquals(emptyList(), ids("\"place usual\""))
        }
}
