package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.model.ComposeIntent
import com.verdenroz.corvid.core.model.Draft
import com.verdenroz.corvid.core.model.EmailAddress
import com.verdenroz.corvid.core.model.EmailDetail
import com.verdenroz.corvid.core.testing.FakeEmail
import com.verdenroz.corvid.core.testing.FakeIdentity
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.FakeMailbox
import com.verdenroz.corvid.core.testing.KeyedTextResolver
import com.verdenroz.corvid.core.testing.SENDABLE_ACCOUNT_EMAIL
import com.verdenroz.corvid.core.testing.client
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

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
    val client = fake.client()
    val mailboxes = MailboxRepository(client)
    return mailboxes to ComposeRepository(client, mailboxes, KeyedTextResolver, TimeZone.UTC)
}

private suspend fun ComposeRepository.sendNew(draft: Draft): SendResult =
    when (val saved = save(draft, replacing = null)) {
        is DraftSaveResult.Saved -> send(saved.emailId, draft.from)
        is DraftSaveResult.Rejected -> SendResult.Rejected(saved.reason)
        is AliasGone -> saved
        is SendUnavailable -> saved
    }

private const val ALIAS = "shop.abcd2345@corvid.test"

private class AliasRig(val fake: FakeJmapServer, val compose: ComposeRepository, val aliases: AliasRepository) {
    suspend fun reply(): Draft = assertIs<StartResult.Ready>(compose.start(ComposeIntent.Reply("orig", all = false))).draft

    fun aliasIdentityId(): String = fake.identities.single { it.email == ALIAS }.id
}

/** An account with a minted alias, whose message arrived on it under the Delivered-To [deliveredTo]. */
private suspend fun aliasRig(deliveredTo: String = ALIAS): AliasRig {
    val fake = server()
    val client = fake.client()
    val aliases = AliasRepository(client)
    assertIs<MintOutcome.Minted>(aliases.mint("shop"))
    fake.emails.replaceAll { it.copy(headers = mapOf("Delivered-To" to listOf(deliveredTo))) }
    return AliasRig(fake, ComposeRepository(client, MailboxRepository(client), KeyedTextResolver, TimeZone.UTC), aliases)
}

private suspend fun MailboxRepository.original(): EmailDetail = assertIs<EmailLookup.Found>(getEmail("orig")).detail

class ComposeRepositoryTest {
    @Test
    fun `a new message starts as an empty draft`() =
        runTest {
            val (_, compose) = repositories(server())

            assertEquals(StartResult.Ready(Draft(), OWN), compose.start(ComposeIntent.New))
        }

    @Test
    fun `the identity and mailboxes are fetched once for the whole session`() =
        runTest {
            val fake = server()
            val (_, compose) = repositories(fake)
            val draft = Draft(to = listOf(EmailAddress(null, "dan@example.test")), body = "hello")
            compose.start(ComposeIntent.New)
            val afterStart = fake.requestCount

            compose.save(draft, replacing = null)
            compose.save(draft, replacing = null)

            assertEquals(afterStart + 2, fake.requestCount)
        }

    @Test
    fun `sending creates the email in drafts and submits it with the identity then files it under sent`() =
        runTest {
            val fake = server()
            val (_, compose) = repositories(fake)

            val result = compose.sendNew(Draft(to = listOf(EmailAddress(null, "dan@example.test")), subject = "Hi", body = "hello"))

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
            compose.sendNew(draft)

            val email = fake.emails.single { it.id == fake.submissions.single().emailId }
            assertEquals(listOf("m1@example.test"), email.inReplyTo)
            assertEquals(listOf("m0@example.test", "m1@example.test"), email.references)
            assertEquals("Re: Lunch", email.subject)
            assertEquals(listOf("alice@example.test"), email.to)
            assertEquals(
                "\n\nreply_attribution[" +
                    "stamp_other_year[day_short_friday, 1, month_short_march, 2024, clock_time[12, 00]], alice@example.test]\n" +
                    "> see you at noon\n>\n> bring cash",
                email.body,
            )
        }

    @Test
    fun `the reply attribution names the original's local day and time, not an ISO stamp`() =
        runTest {
            val (mailboxes, _) = repositories(server())
            val now = Instant.parse("2024-06-01T00:00:00Z")

            val attribution = replyAttribution(mailboxes.original(), TimeZone.of("Europe/London"), now)

            assertEquals(
                uiText(
                    Res.string.reply_attribution,
                    stamp(mailboxes.original().receivedAt, TimeZone.of("Europe/London"), now),
                    UiText.FromServer("alice@example.test"),
                ),
                attribution,
            )
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

            compose.sendNew(Draft(to = listOf(EmailAddress(null, "dan@example.test")), body = "hello"))

            assertEquals(setOf("\$seen"), fake.emails.single { it.id == fake.submissions.single().emailId }.keywords)
        }

    @Test
    fun `reply all excludes the account's own address and the sender from cc`() =
        runTest {
            val (mailboxes, _) = repositories(server())

            val draft = replyDraft(mailboxes.original(), setOf(OWN), all = true, attribution = "")

            assertEquals(listOf(EmailAddress(null, "alice@example.test")), draft.to)
            assertEquals(listOf("bob@example.test", "carol@example.test"), draft.cc.map { it.email })
        }

    @Test
    fun `a subject already carrying the prefix is not prefixed twice`() =
        runTest {
            val (mailboxes, _) = repositories(server())
            val original = mailboxes.original()

            assertEquals("RE: Lunch", replyDraft(original.copy(subject = "RE: Lunch"), setOf(OWN), all = false, attribution = "").subject)
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

            val reply = replyDraft(mailboxes.original(), setOf(OWN), all = false, attribution = "")
            val replyAll = replyDraft(mailboxes.original(), setOf(OWN), all = true, attribution = "")

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
            assertEquals(SendUnavailable.NoIdentity, compose.save(Draft(), replacing = null))
        }

    @Test
    fun `a send uses the main identity when aliases are listed before it`() =
        runTest {
            val fake = server()
            fake.identities.addAll(
                0,
                listOf(FakeIdentity(id = "i0", email = "postmaster@example.test"), FakeIdentity(id = "i9", email = "Shop@example.test")),
            )
            val (_, compose) = repositories(fake)

            compose.sendNew(Draft(to = listOf(EmailAddress(null, "dan@example.test")), body = "hello"))

            assertEquals("i1", fake.submissions.single().identityId)
        }

    @Test
    fun `an account whose main address has no identity cannot send as an alias`() =
        runTest {
            val fake = server()
            fake.identities.clear()
            fake.identities += FakeIdentity(id = "i0", email = "postmaster@example.test")
            val (_, compose) = repositories(fake)

            assertEquals(SendUnavailable.NoIdentity, compose.sendNew(Draft()))
            assertTrue(fake.submissions.isEmpty())
        }

    @Test
    fun `reply all drops every identity of the account from cc`() =
        runTest {
            val fake = server()
            fake.identities += FakeIdentity(id = "i2", email = "shop@example.test")
            fake.emails.replaceAll { it.copy(cc = listOf("carol@example.test", "Shop@example.test")) }
            val (_, compose) = repositories(fake)

            val reply = assertIs<StartResult.Ready>(compose.start(ComposeIntent.Reply("orig", all = true))).draft

            assertEquals(listOf("bob@example.test", "carol@example.test"), reply.cc.map { it.email })
        }

    @Test
    fun `an account with no sent mailbox cannot send and is not cached as ready`() =
        runTest {
            val fake = server()
            val sent = fake.mailboxes.single { it.role == "sent" }
            fake.mailboxes -= sent
            val (_, compose) = repositories(fake)

            assertEquals(SendUnavailable.NoMailbox("sent"), compose.sendNew(Draft()))

            fake.mailboxes += sent
            assertEquals(SendResult.Sent, compose.sendNew(Draft(to = listOf(EmailAddress(null, "dan@example.test")))))
        }

    @Test
    fun `a refused submission is rejected with its reason and delivers nothing`() =
        runTest {
            val fake = server()
            fake.refuseSubmission = true
            val (_, compose) = repositories(fake)

            val result = compose.sendNew(Draft(to = listOf(EmailAddress(null, "dan@example.test")), body = "hello"))

            assertEquals(SendResult.Rejected("submission refused"), result)
            assertTrue(fake.submissions.isEmpty())
            val kept = fake.emails.single { it.id != "orig" }
            assertEquals(setOf("drafts"), kept.mailboxIds)
            assertEquals(setOf("\$draft", "\$seen"), kept.keywords)
        }

    @Test
    fun `a message to a sender starts addressed, with a mailto subject when given`() =
        runTest {
            val (_, compose) = repositories(server())

            val draft = assertIs<StartResult.Ready>(compose.start(ComposeIntent.To("leave@list.test", "unsubscribe"))).draft

            assertEquals(listOf(EmailAddress(null, "leave@list.test")), draft.to)
            assertEquals("unsubscribe", draft.subject)
        }

    private val dan = listOf(EmailAddress(null, "dan@example.test"))

    @Test
    fun `saving files a draft in drafts with the draft keyword and no sent copy`() =
        runTest {
            val fake = server()
            val (_, compose) = repositories(fake)

            val saved = assertIs<DraftSaveResult.Saved>(compose.save(Draft(to = dan, subject = "Hi", body = "hello"), replacing = null))

            val email = fake.emails.single { it.id == saved.emailId }
            assertEquals(setOf("drafts"), email.mailboxIds)
            assertEquals(setOf("\$draft", "\$seen"), email.keywords)
            assertTrue(fake.submissions.isEmpty())
        }

    @Test
    fun `saving again replaces the previous draft`() =
        runTest {
            val fake = server()
            val (_, compose) = repositories(fake)
            val first = assertIs<DraftSaveResult.Saved>(compose.save(Draft(to = dan, body = "one"), replacing = null))
            val before = fake.requestCount

            val second = assertIs<DraftSaveResult.Saved>(compose.save(Draft(to = dan, body = "two"), replacing = first.emailId))

            assertEquals(before + 2, fake.requestCount)
            assertEquals(listOf("two"), fake.emails.filter { "\$draft" in it.keywords }.map { it.body })
            assertEquals(listOf("orig", second.emailId), fake.emails.map { it.id })
        }

    @Test
    fun `a refused save is rejected with its reason`() =
        runTest {
            val fake = server()
            fake.refuseEmailCreate = true
            val (_, compose) = repositories(fake)

            assertEquals(DraftSaveResult.Rejected("email refused"), compose.save(Draft(to = dan), replacing = null))
        }

    @Test
    fun `a saved reply keeps its thread headers when reopened`() =
        runTest {
            val fake = server()
            val (_, compose) = repositories(fake)
            val reply = assertIs<StartResult.Ready>(compose.start(ComposeIntent.Reply("orig", all = true))).draft
            val saved = assertIs<DraftSaveResult.Saved>(compose.save(reply.copy(bcc = dan), replacing = null))

            val reopened = assertIs<StartResult.Ready>(compose.start(ComposeIntent.Draft(saved.emailId))).draft

            assertEquals(reply.copy(bcc = dan), reopened)
        }

    @Test
    fun `reopening an email that is not a draft or is gone says the draft is gone`() =
        runTest {
            val (_, compose) = repositories(server())

            assertEquals(StartResult.DraftGone, compose.start(ComposeIntent.Draft("orig")))
            assertEquals(StartResult.DraftGone, compose.start(ComposeIntent.Draft("nope")))
        }

    @Test
    fun `discarding removes the draft`() =
        runTest {
            val fake = server()
            val (_, compose) = repositories(fake)
            val saved = assertIs<DraftSaveResult.Saved>(compose.save(Draft(to = dan), replacing = null))

            compose.discard(saved.emailId)

            assertEquals(listOf("orig"), fake.emails.map { it.id })
        }

    @Test
    fun `sending moves the saved draft to sent without leaving a draft behind`() =
        runTest {
            val fake = server()
            val (_, compose) = repositories(fake)
            val saved = assertIs<DraftSaveResult.Saved>(compose.save(Draft(to = dan, body = "hello"), replacing = null))

            assertEquals(SendResult.Sent, compose.send(saved.emailId, from = null))

            assertEquals(saved.emailId, fake.submissions.single().emailId)
            assertTrue(fake.emails.none { "\$draft" in it.keywords })
        }

    @Test
    fun `a refused save leaves the previous draft on the server`() =
        runTest {
            val fake = server()
            val (_, compose) = repositories(fake)
            val first = assertIs<DraftSaveResult.Saved>(compose.save(Draft(to = dan, body = "one"), replacing = null))
            fake.refuseEmailCreate = true

            assertIs<DraftSaveResult.Rejected>(compose.save(Draft(to = dan, body = "two"), replacing = first.emailId))

            assertEquals(listOf("one"), fake.emails.filter { "\$draft" in it.keywords }.map { it.body })
        }

    @Test
    fun `a reply is from the alias the original arrived on, and from the main address otherwise`() =
        runTest {
            val (mailboxes, _) = repositories(server())
            val original = mailboxes.original()

            assertEquals(ALIAS, replyDraft(original.copy(arrivedVia = ALIAS), setOf(OWN), all = true, attribution = "").from)
            assertNull(replyDraft(original, setOf(OWN), all = true, attribution = "").from)
        }

    @Test
    fun `a reply to a message that arrived on an alias is sent from that alias`() =
        runTest {
            val rig = aliasRig()
            val reply = rig.reply()

            assertEquals(SendResult.Sent, rig.compose.sendNew(reply))

            assertEquals(ALIAS, reply.from)
            val submission = rig.fake.submissions.single()
            assertEquals(rig.aliasIdentityId(), submission.identityId)
            assertEquals(ALIAS, rig.fake.emails.single { it.id == submission.emailId }.from)
        }

    @Test
    fun `an alias named in another case than its identity still sends from it`() =
        runTest {
            val rig = aliasRig(deliveredTo = ALIAS.uppercase())

            assertEquals(SendResult.Sent, rig.compose.sendNew(rig.reply()))

            assertEquals(rig.aliasIdentityId(), rig.fake.submissions.single().identityId)
        }

    @Test
    fun `a forward and a new message are from the main address even when the original arrived on an alias`() =
        runTest {
            val rig = aliasRig()

            assertNull(assertIs<StartResult.Ready>(rig.compose.start(ComposeIntent.Forward("orig"))).draft.from)
            assertNull(assertIs<StartResult.Ready>(rig.compose.start(ComposeIntent.New)).draft.from)
        }

    @Test
    fun `a reply switched back to the main address is sent from the main identity`() =
        runTest {
            val rig = aliasRig()

            assertEquals(SendResult.Sent, rig.compose.sendNew(rig.reply().copy(from = null)))

            val submission = rig.fake.submissions.single()
            assertEquals("i1", submission.identityId)
            assertEquals(OWN, rig.fake.emails.single { it.id == submission.emailId }.from)
        }

    @Test
    fun `a draft naming the main address in another case is sent from the main identity`() =
        runTest {
            val fake = server()
            val (_, compose) = repositories(fake)

            assertEquals(SendResult.Sent, compose.sendNew(Draft(to = dan, from = OWN.uppercase())))

            assertEquals("i1", fake.submissions.single().identityId)
        }

    @Test
    fun `a reply from an alias disabled since it arrived is refused and never sent from the main address`() =
        runTest {
            val rig = aliasRig()
            val reply = rig.reply()
            val saved = assertIs<DraftSaveResult.Saved>(rig.compose.save(reply, replacing = null))

            assertIs<SwitchOutcome.Switched>(rig.aliases.disable("shop.abcd2345", "master-token"))

            assertEquals(AliasGone(ALIAS), rig.compose.send(saved.emailId, reply.from))
            assertEquals(AliasGone(ALIAS), rig.compose.save(reply.copy(body = "edited"), replacing = saved.emailId))
            assertTrue(rig.fake.submissions.isEmpty())
            assertEquals(listOf(saved.emailId), rig.fake.emails.filter { "\$draft" in it.keywords }.map { it.id })
        }

    @Test
    fun `a reply from an alias enabled again is sent under its new identity`() =
        runTest {
            val rig = aliasRig()
            val reply = rig.reply()
            val before = rig.aliasIdentityId()
            assertIs<SwitchOutcome.Switched>(rig.aliases.disable("shop.abcd2345", "master-token"))
            assertIs<SwitchOutcome.Switched>(rig.aliases.enable("shop.abcd2345", "master-token"))

            assertEquals(SendResult.Sent, rig.compose.sendNew(reply))

            assertNotEquals(before, rig.aliasIdentityId())
            assertEquals(rig.aliasIdentityId(), rig.fake.submissions.single().identityId)
        }

    @Test
    fun `a saved reply from an alias reopens from that alias`() =
        runTest {
            val rig = aliasRig()
            val reply = rig.reply()
            val saved = assertIs<DraftSaveResult.Saved>(rig.compose.save(reply, replacing = null))

            val reopened = assertIs<StartResult.Ready>(rig.compose.start(ComposeIntent.Draft(saved.emailId))).draft

            assertEquals(reply, reopened)
        }
}
