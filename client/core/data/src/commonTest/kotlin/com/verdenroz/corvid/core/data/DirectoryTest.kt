package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.model.CorvidKeywords
import com.verdenroz.corvid.core.model.Label
import com.verdenroz.corvid.core.model.Seal
import com.verdenroz.corvid.core.model.SourceKind
import com.verdenroz.corvid.core.testing.FakeEmail
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.SENDABLE_ACCOUNT_EMAIL
import com.verdenroz.corvid.core.testing.client
import com.verdenroz.corvid.core.testing.withRoleMailboxes
import com.verdenroz.corvid.core.testing.withSendableAccount
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

private const val OWN = SENDABLE_ACCOUNT_EMAIL
private val NEWSLETTER = mapOf("List-Unsubscribe" to listOf("<https://shop.test/unsubscribe>"))
private val SIGNED = mapOf("Authentication-Results" to listOf("mx; dmarc=pass header.from=shop.test"))

private fun server() = FakeJmapServer().withSendableAccount().withRoleMailboxes(INBOX, SENT, DRAFTS, TRASH, JUNK)

private fun email(
    id: String,
    mailbox: String,
    from: String,
    day: Int,
    headers: Map<String, List<String>> = emptyMap(),
) = FakeEmail(id, id, setOf(mailbox), id, from, "2026-10-${day.toString().padStart(2, '0')}T08:00:00Z", headers = headers)

private suspend fun FakeJmapServer.directory() = MailboxRepository(client()).directory()

class DirectoryTest {
    @Test
    fun `a sender whose newest message came from a service is a source and anyone else is a person`() =
        runTest {
            val fake = server()
            fake.emails += email("a1", INBOX, "ada@friends.test", 1).copy(fromName = "Ada")
            fake.emails += email("a2", INBOX, "ada@friends.test", 3)
            fake.emails += email("n1", INBOX, "news@shop.test", 2, NEWSLETTER + SIGNED)

            val directory = fake.directory()

            assertEquals(3, directory.window)
            val ada = directory.people.single()
            assertEquals("Ada", ada.address.name)
            assertEquals(2, ada.received)
            assertEquals(Instant.parse("2026-10-03T08:00:00Z"), ada.last)
            val shop = directory.sources.single()
            assertEquals("news@shop.test", shop.address.email)
            assertEquals(SourceKind.Newsletter, shop.kind)
            assertEquals(Seal.Signed("shop.test"), shop.seal)
        }

    @Test
    fun `junk and trash are left out of the window and everything counted over it`() =
        runTest {
            val fake = server()
            fake.emails += email("i", INBOX, "ada@friends.test", 1)
            fake.emails += email("j", JUNK, "scam@bad.test", 2).copy(keywords = setOf("prize"))
            fake.emails += email("t", TRASH, "ada@friends.test", 3)

            val directory = fake.directory()

            assertEquals(1, directory.window)
            assertEquals(listOf("ada@friends.test"), directory.people.map { it.address.email })
            assertEquals(1, directory.people.single().received)
            assertEquals(emptyList(), directory.labels)
        }

    @Test
    fun `someone the account only wrote to is a person with no seal, and a draft counts for nobody`() =
        runTest {
            val fake = server()
            fake.emails += email("s1", SENT, OWN, 1).copy(to = listOf("bo@friends.test"), cc = listOf(OWN))
            fake.emails += email("s2", SENT, OWN, 4).copy(to = listOf("bo@friends.test"))
            fake.emails +=
                email("d", DRAFTS, OWN, 5).copy(to = listOf("cy@friends.test"), keywords = setOf(CorvidKeywords.DRAFT))

            val bo = fake.directory().people.single()

            assertEquals("bo@friends.test", bo.address.email)
            assertEquals(2, bo.sent)
            assertEquals(0, bo.received)
            assertEquals(Instant.parse("2026-10-04T08:00:00Z"), bo.last)
            assertNull(bo.seal)
        }

    @Test
    fun `a source's tracker count covers only the messages that carried a record`() =
        runTest {
            val fake = server()
            val stripped = mapOf("X-Corvid-Rewrite" to listOf("stripped=3 proxied=1 hosts=2 trackers=2"))
            fake.emails += email("n1", INBOX, "news@shop.test", 1, NEWSLETTER + stripped)
            fake.emails += email("n2", INBOX, "news@shop.test", 2, NEWSLETTER + mapOf("X-Corvid-Rewrite" to listOf("none")))
            fake.emails += email("n3", INBOX, "news@shop.test", 3, NEWSLETTER)

            val shop = fake.directory().sources.single()

            assertEquals(3, shop.received)
            assertEquals(2, shop.marked)
            assertEquals(3, shop.trackersStripped)
        }

    @Test
    fun `labels are counted by exact keyword with who set them, and flags are not labels`() =
        runTest {
            val fake = server()
            val keywords = setOf("travel", "corvid-receipts", "corvid-llm-label-receipts", "corvid-llm-done", CorvidKeywords.SEEN)
            fake.emails += email("a", INBOX, "ada@friends.test", 1).copy(keywords = keywords)
            fake.emails += email("b", INBOX, "ada@friends.test", 2).copy(keywords = setOf("corvid-rule-bills", "travel"))

            val labels = fake.directory().labels

            assertEquals(
                listOf(
                    LabelCount(Label.Yours("travel"), "travel", 2),
                    LabelCount(Label.Rule("bills"), "corvid-rule-bills", 1),
                    LabelCount(Label.Guess("receipts"), "corvid-llm-label-receipts", 1),
                    LabelCount(Label.Labeler("receipts"), "corvid-receipts", 1),
                ),
                labels,
            )
        }

    @Test
    fun `each directory reads the newest messages again`() =
        runTest {
            val fake = server()
            fake.emails += email("a", INBOX, "ada@friends.test", 1)
            val repository = MailboxRepository(fake.client())
            repository.directory()
            fake.emails += email("b", INBOX, "bo@friends.test", 2)

            assertEquals(2, repository.directory().people.size)
        }
}
