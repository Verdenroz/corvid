package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.model.CorvidKeywords
import com.verdenroz.corvid.core.model.EmailAddress
import com.verdenroz.corvid.core.model.Provenance
import com.verdenroz.corvid.core.model.Seal
import com.verdenroz.corvid.core.model.SenderKind
import com.verdenroz.corvid.core.testing.FakeEmail
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.SENDABLE_ACCOUNT_EMAIL
import com.verdenroz.corvid.core.testing.client
import com.verdenroz.corvid.core.testing.withSendableAccount
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

private const val OWN = SENDABLE_ACCOUNT_EMAIL

private fun sampled(
    from: String,
    to: List<String> = emptyList(),
    at: String = "2024-01-01T00:00:00Z",
    fromName: String? = null,
    draft: Boolean = false,
) = SampledEmail(
    from = listOf(EmailAddress(fromName, from)),
    to = to.map { EmailAddress(null, it) },
    cc = emptyList(),
    subject = null,
    preview = null,
    receivedAt = Instant.parse(at),
    keywords = if (draft) setOf(CorvidKeywords.DRAFT) else emptySet(),
    mailboxIds = emptySet(),
    provenance = Provenance(Seal.Unsigned, null, SenderKind.Person, emptyList()),
)

private fun List<Contact>.emails() = map { it.address.email }

private fun contact(
    email: String,
    name: String? = null,
) = Contact(EmailAddress(name, email), sent = 0, seen = 1, lastSeen = Instant.DISTANT_PAST)

class ContactsTest {
    @Test
    fun `someone the account wrote to ranks above a sender who writes more often`() {
        val emails =
            listOf(
                sampled("news@shop.test"),
                sampled("news@shop.test"),
                sampled("news@shop.test"),
                sampled(OWN, to = listOf("ada@example.test")),
            )

        assertEquals(listOf("ada@example.test", "news@shop.test"), rankContacts(emails, setOf(OWN)).emails())
    }

    @Test
    fun `among equals the one seen more often comes first, then the one seen more recently`() {
        val emails =
            listOf(
                sampled("old@example.test", at = "2024-01-01T00:00:00Z"),
                sampled("new@example.test", at = "2024-02-01T00:00:00Z"),
                sampled("often@example.test", at = "2023-01-01T00:00:00Z"),
                sampled("often@example.test", at = "2023-01-02T00:00:00Z"),
            )

        assertEquals(
            listOf("often@example.test", "new@example.test", "old@example.test"),
            rankContacts(emails, setOf(OWN)).emails(),
        )
    }

    @Test
    fun `the account's own identities are never suggested, whatever their case`() {
        val emails = listOf(sampled("ada@example.test", to = listOf("Verde@Example.test")))

        assertEquals(listOf("ada@example.test"), rankContacts(emails, setOf(OWN)).emails())
    }

    @Test
    fun `no-reply style addresses are left out`() {
        val senders = listOf("noreply@a.test", "no-reply@b.test", "do_not_reply@c.test", "DoNotReply@d.test", "MAILER-DAEMON@e.test")
        val emails = senders.map { sampled(it) } + sampled("ada@example.test")

        assertEquals(listOf("ada@example.test"), rankContacts(emails, setOf(OWN)).emails())
    }

    @Test
    fun `a draft counts for nobody`() {
        val emails = listOf(sampled(OWN, to = listOf("ada@example.test"), draft = true))

        assertEquals(emptyList(), rankContacts(emails, setOf(OWN)))
    }

    @Test
    fun `one person under two spellings of their address is one contact named from the newest message`() {
        val emails =
            listOf(
                sampled("Ada@Example.test", at = "2024-01-01T00:00:00Z", fromName = "Ada L"),
                sampled("ada@example.test", at = "2024-02-01T00:00:00Z", fromName = "Ada Lovelace"),
            )

        val contact = rankContacts(emails, setOf(OWN)).single()

        assertEquals("Ada Lovelace", contact.address.name)
        assertEquals(2, contact.seen)
        assertEquals(Instant.parse("2024-02-01T00:00:00Z"), contact.lastSeen)
    }

    @Test
    fun `a match at the start of the address or a word of the name comes before one inside it`() {
        val contacts = listOf(contact("thomas@example.test"), contact("x@example.test", "Grace Hopper"), contact("ho@example.test"))

        assertEquals(listOf("x@example.test", "ho@example.test", "thomas@example.test"), contacts.matching("HO", 6).emails())
    }

    @Test
    fun `matching stops at the limit and finds nothing for blank text`() {
        val contacts = (1..9).map { contact("a$it@example.test") }

        assertEquals(6, contacts.matching("a", 6).size)
        assertEquals(emptyList(), contacts.matching("  ", 6))
    }

    @Test
    fun `contacts and search completion share one fetch of the sample`() =
        runTest {
            var queries = 0
            val fake =
                FakeJmapServer().withSendableAccount().apply {
                    emails += FakeEmail("m1", "m1", setOf("sent"), "Hi", OWN, "2024-01-01T00:00:00Z", to = listOf("ada@example.test"))
                    emails +=
                        FakeEmail("m2", "m2", setOf("drafts"), "Draft", OWN, "2024-01-02T00:00:00Z", to = listOf("bob@example.test"))
                            .copy(keywords = setOf(CorvidKeywords.DRAFT))
                    beforeEmailQuery = { queries++ }
                }
            val repository = MailboxRepository(fake.client())

            val contacts = repository.contacts()
            repository.page(MailFilter.Search("ada"), anchor = null)

            assertEquals(listOf("ada@example.test"), contacts.emails())
            assertEquals(2, queries)
        }
}
