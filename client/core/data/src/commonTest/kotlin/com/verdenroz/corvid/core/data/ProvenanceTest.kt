package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.model.Rewrite
import com.verdenroz.corvid.core.model.Seal
import com.verdenroz.corvid.core.model.SenderKind
import com.verdenroz.corvid.core.model.SourceKind
import com.verdenroz.corvid.core.testing.FakeEmail
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.client
import com.verdenroz.corvid.core.testing.withSendableAccount
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

private fun email(
    id: String,
    from: String,
    headers: Map<String, List<String>>,
) = FakeEmail(id, id, setOf("inbox"), "s", from, "2024-01-01T00:00:00Z", headers = headers, body = "b")

private suspend fun repository(fake: FakeJmapServer) = MailboxRepository(fake.client())

private suspend fun inboxSummary(fake: FakeJmapServer) =
    assertIs<EmailPage.Found>(repository(fake).page(MailFilter.InMailbox("inbox"), null)).conversations.single().latest

class ProvenanceTest {
    @Test
    fun `the inbox list carries each message's seal, tracker count and sender kind`() =
        runTest {
            val fake = FakeJmapServer().withSendableAccount()
            fake.emails +=
                email(
                    "news",
                    "editors@browser.test",
                    mapOf(
                        "Authentication-Results" to listOf("mx; dmarc=pass header.from=browser.test"),
                        "X-Corvid-Rewrite" to listOf("stripped=6 proxied=2 hosts=3 trackers=2"),
                        "List-Unsubscribe" to listOf("<mailto:leave@browser.test>, <https://browser.test/u>"),
                    ),
                )

            val summary = inboxSummary(fake)

            assertEquals(Seal.Signed("browser.test"), summary.provenance.seal)
            assertEquals(6, assertIs<Rewrite.Stripped>(summary.provenance.rewrite).stripped)
            assertEquals(SenderKind.Source(SourceKind.Newsletter), summary.provenance.sender)
            assertEquals(listOf("mailto:leave@browser.test", "https://browser.test/u"), summary.provenance.unsubscribe)
        }

    @Test
    fun `a list message keeps its list id and any other has none`() =
        runTest {
            val fake = FakeJmapServer().withSendableAccount()
            fake.emails += email("list", "deals@ledgerly.test", mapOf("List-Id" to listOf("Deals <deals.ledgerly.test>")))
            fake.emails += email("plain", "ada@analytical.test", emptyMap())

            val repository = repository(fake)

            assertEquals("Deals <deals.ledgerly.test>", assertIs<EmailLookup.Found>(repository.getEmail("list")).detail.provenance.listId)
            assertNull(assertIs<EmailLookup.Found>(repository.getEmail("plain")).detail.provenance.listId)
        }

    @Test
    fun `a message the milter never saw shows no tracker count and nobody's seal`() =
        runTest {
            val fake = FakeJmapServer().withSendableAccount()
            fake.emails += email("plain", "ada@analytical.test", emptyMap())

            val provenance = assertIs<EmailLookup.Found>(repository(fake).getEmail("plain")).detail.provenance

            assertNull(provenance.rewrite)
            assertEquals(Seal.Unsigned, provenance.seal)
            assertEquals(SenderKind.Person, provenance.sender)
        }

    @Test
    fun `the reader ignores a forged pass and a forged tracker header below the server's own`() =
        runTest {
            val fake = FakeJmapServer().withSendableAccount()
            fake.emails +=
                email(
                    "phish",
                    "service@paypa1-secure.test",
                    mapOf(
                        "Authentication-Results" to
                            listOf(
                                "mx; dkim=pass header.d=paypa1-secure.test; dmarc=fail header.from=paypal.test",
                                "mx; dmarc=pass header.from=paypal.test",
                            ),
                        "X-Corvid-Rewrite" to listOf("none", "stripped=4 proxied=0 hosts=1 trackers=4"),
                    ),
                )

            val provenance = assertIs<EmailLookup.Found>(repository(fake).getEmail("phish")).detail.provenance

            assertEquals(Seal.Failed(claimed = "paypal.test", signedBy = "paypa1-secure.test"), provenance.seal)
            assertNull(provenance.rewrite)
        }

    @Test
    fun `automated mail from the account's own domain is your server`() =
        runTest {
            val fake = FakeJmapServer().withSendableAccount()
            fake.emails += email("s", "sentinel@example.test", mapOf("Auto-Submitted" to listOf("auto-generated")))

            val summary = inboxSummary(fake)

            assertEquals(SenderKind.Source(SourceKind.OwnServer), summary.provenance.sender)
        }
}
