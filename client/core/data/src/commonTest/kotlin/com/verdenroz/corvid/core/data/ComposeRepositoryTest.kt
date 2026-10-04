package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.jmap.AuthLatch
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.createJmapHttpClient
import com.verdenroz.corvid.core.model.ComposeIntent
import com.verdenroz.corvid.core.model.Draft
import com.verdenroz.corvid.core.model.EmailAddress
import com.verdenroz.corvid.core.model.EmailDetail
import com.verdenroz.corvid.core.testing.FakeEmail
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.FakeMailbox
import com.verdenroz.corvid.core.testing.KeyedTextResolver
import com.verdenroz.corvid.core.testing.SENDABLE_ACCOUNT_EMAIL
import com.verdenroz.corvid.core.testing.withSendableAccount
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.resources.Res
import com.verdenroz.corvid.core.text.resources.reply_attribution
import com.verdenroz.corvid.core.text.uiText
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

private const val BASE_URL = "http://127.0.0.1:9999"
private const val OWN = SENDABLE_ACCOUNT_EMAIL

private fun server(): FakeJmapServer {
    val fake = FakeJmapServer().withSendableAccount()
    fake.mailboxes += FakeMailbox(id = "inbox", name = "Inbox", role = "inbox")
    fake.emails +=
        FakeEmail(
            id = "orig",
            threadId = "t1",
            mailboxIds = setOf("inbox"),
            subject = "Lunch",
            from = "alice@example.test",
            receivedAt = "2024-03-01T12:00:00Z",
            to = listOf(OWN, "bob@example.test"),
            cc = listOf("carol@example.test", OWN),
            body = "see you at noon\n\nbring cash",
            messageId = listOf("m1@example.test"),
            references = listOf("m0@example.test"),
        )
    return fake
}

private suspend fun repositories(fake: FakeJmapServer): Pair<MailboxRepository, ComposeRepository> {
    val client = JmapClient.connect(createJmapHttpClient(fake.engine), AuthLatch(), BASE_URL)
    val mailboxes = MailboxRepository(client)
    return mailboxes to ComposeRepository(client, mailboxes, KeyedTextResolver, TimeZone.UTC)
}

private suspend fun MailboxRepository.original(): EmailDetail = assertIs<EmailLookup.Found>(getEmail("orig")).detail

class ComposeRepositoryTest {
    @Test
    fun `a new message starts as an empty draft`() =
        runTest {
            val (_, compose) = repositories(server())

            assertEquals(StartResult.Ready(Draft()), compose.start(ComposeIntent.New))
        }

    @Test
    fun `the identity and mailboxes are fetched once for the whole session`() =
        runTest {
            val fake = server()
            val (_, compose) = repositories(fake)
            val draft = Draft(to = listOf(EmailAddress(null, "dan@example.test")), body = "hello")
            compose.start(ComposeIntent.New)
            val afterStart = fake.requestCount

            compose.send(draft)
            compose.send(draft)

            assertEquals(afterStart + 2, fake.requestCount)
        }

    @Test
    fun `sending creates the email in drafts and submits it with the identity then files it under sent`() =
        runTest {
            val fake = server()
            val (_, compose) = repositories(fake)

            val result = compose.send(Draft(to = listOf(EmailAddress(null, "dan@example.test")), subject = "Hi", body = "hello"))

            assertEquals(SendResult.Sent, result)
            val submission = fake.submissions.single()
            assertEquals("i1", submission.identityId)
            val email = fake.emails.single { it.id == submission.emailId }
            assertEquals(setOf("sent"), email.mailboxIds)
            assertEquals(setOf("\$seen"), email.keywords)
            assertEquals(listOf("dan@example.test"), email.to)
            assertEquals(OWN, email.from)
            assertEquals("hello", email.body)
        }

    @Test
    fun `a reply carries in-reply-to and references and quotes the original`() =
        runTest {
            val fake = server()
            val (_, compose) = repositories(fake)

            val draft = assertIs<StartResult.Ready>(compose.start(ComposeIntent.Reply("orig", all = false))).draft
            compose.send(draft)

            val email = fake.emails.single { it.id == fake.submissions.single().emailId }
            assertEquals(listOf("m1@example.test"), email.inReplyTo)
            assertEquals(listOf("m0@example.test", "m1@example.test"), email.references)
            assertEquals("Re: Lunch", email.subject)
            assertEquals(listOf("alice@example.test"), email.to)
            assertEquals(
                "\n\nreply_attribution[Fri 1 Mar 2024, 12:00, alice@example.test]\n> see you at noon\n>\n> bring cash",
                email.body,
            )
        }

    @Test
    fun `the reply attribution names the original's local day and time, not an ISO stamp`() =
        runTest {
            val (mailboxes, _) = repositories(server())
            val now = Instant.parse("2024-06-01T00:00:00Z")

            val attribution = replyAttribution(mailboxes.original(), TimeZone.of("Europe/London"), now)

            assertEquals(uiText(Res.string.reply_attribution, "Fri 1 Mar, 12:00", UiText.FromServer("alice@example.test")), attribution)
        }

    @Test
    fun `a reply to a message deleted before compose opened says the original is gone`() =
        runTest {
            val fake = server()
            val (_, compose) = repositories(fake)
            fake.emails.clear()

            assertEquals(StartResult.OriginalGone, compose.start(ComposeIntent.Reply("orig", all = false)))
            assertEquals(StartResult.OriginalGone, compose.start(ComposeIntent.Forward("orig")))
        }

    @Test
    fun `a sent message is filed as read`() =
        runTest {
            val fake = server()
            val (_, compose) = repositories(fake)

            compose.send(Draft(to = listOf(EmailAddress(null, "dan@example.test")), body = "hello"))

            assertEquals(setOf("\$seen"), fake.emails.single { it.id == fake.submissions.single().emailId }.keywords)
        }

    @Test
    fun `reply all excludes the account's own address and the sender from cc`() =
        runTest {
            val (mailboxes, _) = repositories(server())

            val draft = replyDraft(mailboxes.original(), OWN, all = true, attribution = "")

            assertEquals(listOf(EmailAddress(null, "alice@example.test")), draft.to)
            assertEquals(listOf("bob@example.test", "carol@example.test"), draft.cc.map { it.email })
        }

    @Test
    fun `a subject already carrying the prefix is not prefixed twice`() =
        runTest {
            val (mailboxes, _) = repositories(server())
            val original = mailboxes.original()

            assertEquals("RE: Lunch", replyDraft(original.copy(subject = "RE: Lunch"), OWN, all = false, attribution = "").subject)
            assertEquals("fwd: Lunch", forwardDraft(original.copy(subject = "fwd: Lunch"), divider = "", date = "").subject)
        }

    @Test
    fun `a forward puts the original below a header block and addresses nobody`() =
        runTest {
            val (mailboxes, _) = repositories(server())

            val draft = forwardDraft(mailboxes.original(), divider = "-- forwarded --", date = "Fri 1 Mar 2024, 12:00")

            assertEquals("Fwd: Lunch", draft.subject)
            assertTrue(draft.to.isEmpty())
            assertTrue(draft.inReplyTo.isEmpty())
            assertEquals(
                "\n\n-- forwarded --\n" +
                    "From: alice@example.test\n" +
                    "Date: Fri 1 Mar 2024, 12:00\n" +
                    "Subject: Lunch\n" +
                    "To: $OWN, bob@example.test\n" +
                    "Cc: carol@example.test, $OWN\n\n" +
                    "see you at noon\n\nbring cash",
                draft.body,
            )
        }

    @Test
    fun `a reply goes to the reply-to address and keeps the sender in reply all`() =
        runTest {
            val fake = server()
            fake.emails.replaceAll { it.copy(replyTo = listOf("list@example.test")) }
            val (mailboxes, _) = repositories(fake)

            val reply = replyDraft(mailboxes.original(), OWN, all = false, attribution = "")
            val replyAll = replyDraft(mailboxes.original(), OWN, all = true, attribution = "")

            assertEquals(listOf("list@example.test"), reply.to.map { it.email })
            assertEquals(listOf("list@example.test"), replyAll.to.map { it.email })
            assertEquals(listOf("bob@example.test", "carol@example.test", "alice@example.test"), replyAll.cc.map { it.email })
        }

    @Test
    fun `an account with no identity cannot start or send`() =
        runTest {
            val fake = server()
            fake.identities.clear()
            val (_, compose) = repositories(fake)

            assertEquals(SendUnavailable.NoIdentity, compose.start(ComposeIntent.New))
            assertEquals(SendUnavailable.NoIdentity, compose.send(Draft()))
        }

    @Test
    fun `an account with no sent mailbox cannot send and is not cached as ready`() =
        runTest {
            val fake = server()
            val sent = fake.mailboxes.single { it.role == "sent" }
            fake.mailboxes -= sent
            val (_, compose) = repositories(fake)

            assertEquals(SendUnavailable.NoMailbox("sent"), compose.send(Draft()))

            fake.mailboxes += sent
            assertEquals(SendResult.Sent, compose.send(Draft(to = listOf(EmailAddress(null, "dan@example.test")))))
        }

    @Test
    fun `a refused submission is rejected with its reason and delivers nothing`() =
        runTest {
            val fake = server()
            fake.refuseSubmission = true
            val (_, compose) = repositories(fake)

            val result = compose.send(Draft(to = listOf(EmailAddress(null, "dan@example.test")), body = "hello"))

            assertEquals(SendResult.Rejected("submission refused"), result)
            assertTrue(fake.submissions.isEmpty())
            assertEquals(listOf("orig"), fake.emails.map { it.id })
        }
}
