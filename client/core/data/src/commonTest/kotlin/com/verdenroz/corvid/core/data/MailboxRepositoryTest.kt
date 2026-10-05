package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.model.EmailSummary
import com.verdenroz.corvid.core.model.HtmlBody
import com.verdenroz.corvid.core.testing.FakeEmail
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.FakeMailbox
import com.verdenroz.corvid.core.testing.SENDABLE_ACCOUNT_EMAIL
import com.verdenroz.corvid.core.testing.client
import com.verdenroz.corvid.core.testing.withSendableAccount
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

private const val EMAIL_COUNT = 170

private suspend fun repository(fake: FakeJmapServer): MailboxRepository {
    val client = fake.client()
    return MailboxRepository(client)
}

class MailboxRepositoryTest {
    @Test
    fun `the inbox mailbox is found by role`() =
        runTest {
            val fake = FakeJmapServer()
            fake.mailboxes += FakeMailbox(id = "m1", name = "Archive", role = "archive")
            fake.mailboxes += FakeMailbox(id = "m2", name = "Inbox", role = "inbox")

            val inbox = repository(fake).findMailboxByRole("inbox")

            assertEquals("m2", inbox?.id)
        }

    @Test
    fun `a text search finds messages in any mailbox, newest first`() =
        runTest {
            val fake = FakeJmapServer()

            fun email(
                id: String,
                mailbox: String,
                subject: String,
                seconds: Long,
                preview: String = "",
            ) = FakeEmail(
                id = id,
                threadId = id,
                mailboxIds = setOf(mailbox),
                subject = subject,
                from = "sender@example.test",
                receivedAt = Instant.fromEpochSeconds(seconds).toString(),
                preview = preview,
            )
            fake.emails += email("old", "inbox", "Your INVOICE", seconds = 1)
            fake.emails += email("other", "inbox", "Lunch", seconds = 2)
            fake.emails += email("archived", "archive", "Re: lunch", seconds = 3, preview = "the invoice is attached")

            val page = assertIs<EmailPage.Found>(repository(fake).page(MailFilter.Search("invoice"), null))

            assertEquals(listOf("archived", "old"), page.emails.map { it.id })
        }

    @Test
    fun `a mailbox larger than three pages is listed without gaps or repeats`() =
        runTest {
            val fake = FakeJmapServer()
            repeat(EMAIL_COUNT) { i ->
                fake.emails +=
                    FakeEmail(
                        id = "e$i",
                        threadId = "e$i",
                        mailboxIds = setOf("inbox"),
                        subject = "subject $i",
                        from = "sender$i@example.test",
                        receivedAt = Instant.fromEpochSeconds((EMAIL_COUNT - i).toLong()).toString(),
                    )
            }
            // subject and from are String|null and EmailAddress[]|null on the wire (RFC 8621);
            // both need to survive one full traversal without a crash or a literal "null".
            fake.emails +=
                FakeEmail(
                    id = "no-subject",
                    threadId = "no-subject",
                    mailboxIds = setOf("inbox"),
                    subject = null,
                    from = "sender@example.test",
                    receivedAt = Instant.fromEpochSeconds((EMAIL_COUNT + 1).toLong()).toString(),
                )
            fake.emails +=
                FakeEmail(
                    id = "no-from",
                    threadId = "no-from",
                    mailboxIds = setOf("inbox"),
                    subject = "subject",
                    from = null,
                    receivedAt = Instant.fromEpochSeconds((EMAIL_COUNT + 2).toLong()).toString(),
                )
            val repository = repository(fake)

            val all = mutableListOf<EmailSummary>()
            var anchor: String? = null
            do {
                val page = assertIs<EmailPage.Found>(repository.page(MailFilter.InMailbox("inbox"), anchor))
                all += page.emails
                anchor = page.emails.lastOrNull()?.id
            } while (page.emails.isNotEmpty())

            assertEquals(EMAIL_COUNT + 2, all.size)
            assertEquals(all.map { it.id }.toSet().size, all.size)
            assertEquals("", all.first { it.id == "no-subject" }.subject)
            assertNull(all.first { it.id == "no-from" }.from)
        }

    @Test
    fun `an anchor the server no longer recognises restarts the query from the top`() =
        runTest {
            val fake = FakeJmapServer()
            fake.emails += FakeEmail("e1", "e1", setOf("inbox"), "s1", "a@example.test", "2024-01-01T00:00:00Z")

            val page = repository(fake).page(MailFilter.InMailbox("inbox"), anchor = "does-not-exist")

            assertIs<EmailPage.AnchorNotFound>(page)
        }

    @Test
    fun `a plain text message is read as is`() =
        runTest {
            val fake = FakeJmapServer()
            fake.emails +=
                FakeEmail(
                    "e1",
                    "e1",
                    setOf("inbox"),
                    "hello",
                    "a@example.test",
                    "2024-01-01T00:00:00Z",
                    to = listOf("b@example.test"),
                    cc = listOf("c@example.test"),
                    body = "plain text body",
                )

            val detail = assertIs<EmailLookup.Found>(repository(fake).getEmail("e1")).detail

            assertEquals("plain text body", detail.body)
            assertEquals(listOf("b@example.test"), detail.to.map { it.email })
            assertEquals(listOf("c@example.test"), detail.cc.map { it.email })
            assertEquals(false, detail.bodyTruncated)
        }

    @Test
    fun `an html-only message gets a flattened plain body and keeps its html`() =
        runTest {
            val fake = FakeJmapServer()
            fake.emails +=
                FakeEmail(
                    "e1",
                    "e1",
                    setOf("inbox"),
                    "hello",
                    "a@example.test",
                    "2024-01-01T00:00:00Z",
                    bodyType = "text/html",
                    body = "<p>hello <b>world</b></p>",
                )

            val detail = assertIs<EmailLookup.Found>(repository(fake).getEmail("e1")).detail

            assertEquals("hello world", detail.body)
            assertEquals(HtmlBody("<p>hello <b>world</b></p>", truncated = false), detail.html)
        }

    @Test
    fun `an alternative message keeps its plain body and its html part`() =
        runTest {
            val fake = FakeJmapServer()
            fake.emails +=
                FakeEmail(
                    "e1",
                    "e1",
                    setOf("inbox"),
                    "hello",
                    "a@example.test",
                    "2024-01-01T00:00:00Z",
                    body = "hello world",
                    htmlBody = "<p>hello <b>world</b></p>",
                    bodyTruncated = true,
                )

            val detail = assertIs<EmailLookup.Found>(repository(fake).getEmail("e1")).detail

            assertEquals("hello world", detail.body)
            assertEquals(HtmlBody("<p>hello <b>world</b></p>", truncated = true), detail.html)
        }

    @Test
    fun `a plain-text message has no html`() =
        runTest {
            val fake = FakeJmapServer()
            fake.emails += FakeEmail("e1", "e1", setOf("inbox"), "hi", "a@example.test", "2024-01-01T00:00:00Z", body = "just text")

            val detail = assertIs<EmailLookup.Found>(repository(fake).getEmail("e1")).detail

            assertNull(detail.html)
        }

    @Test
    fun `paragraphs and list items keep their line breaks when flattened`() =
        runTest {
            val fake = FakeJmapServer()
            fake.emails +=
                FakeEmail(
                    "e1",
                    "e1",
                    setOf("inbox"),
                    "hello",
                    "a@example.test",
                    "2024-01-01T00:00:00Z",
                    bodyType = "text/html",
                    body = "<p>first paragraph</p><p>second paragraph</p><ul><li>one</li><li>two</li></ul>",
                )

            val detail = assertIs<EmailLookup.Found>(repository(fake).getEmail("e1")).detail

            assertEquals("first paragraph\n\nsecond paragraph\n\none\n\ntwo", detail.body)
        }

    @Test
    fun `a pre block keeps its indentation when the rest of the body is trimmed`() =
        runTest {
            val fake = FakeJmapServer()
            fake.emails +=
                FakeEmail(
                    "e1",
                    "e1",
                    setOf("inbox"),
                    "hello",
                    "a@example.test",
                    "2024-01-01T00:00:00Z",
                    bodyType = "text/html",
                    body = "<p>before</p><pre>  line one\n    line two</pre><p>after</p>",
                )

            val detail = assertIs<EmailLookup.Found>(repository(fake).getEmail("e1")).detail

            assertEquals("before\n\n  line one\n    line two\n\nafter", detail.body)
        }

    @Test
    fun `entities in an html-only body are decoded, not shown escaped`() =
        runTest {
            val fake = FakeJmapServer()
            fake.emails +=
                FakeEmail(
                    "e1",
                    "e1",
                    setOf("inbox"),
                    "hello",
                    "a@example.test",
                    "2024-01-01T00:00:00Z",
                    bodyType = "text/html",
                    body = "<p>Tom &amp; Jerry, a &lt; b</p>",
                )

            val detail = assertIs<EmailLookup.Found>(repository(fake).getEmail("e1")).detail

            assertEquals("Tom & Jerry, a < b", detail.body)
        }

    @Test
    fun `a literal backslash-n in the mail's own text is not mistaken for a line break`() =
        runTest {
            val fake = FakeJmapServer()
            fake.emails +=
                FakeEmail(
                    "e1",
                    "e1",
                    setOf("inbox"),
                    "hello",
                    "a@example.test",
                    "2024-01-01T00:00:00Z",
                    bodyType = "text/html",
                    body = """<p>path is C:\ndir</p>""",
                )

            val detail = assertIs<EmailLookup.Found>(repository(fake).getEmail("e1")).detail

            assertEquals("""path is C:\ndir""", detail.body)
        }

    @Test
    fun `a script or style element's contents are never shown`() =
        runTest {
            val fake = FakeJmapServer()
            fake.emails +=
                FakeEmail(
                    "e1",
                    "e1",
                    setOf("inbox"),
                    "hello",
                    "a@example.test",
                    "2024-01-01T00:00:00Z",
                    bodyType = "text/html",
                    body = "<style>p { color: red }</style><script>alert('hi')</script><p>visible text</p>",
                )

            val detail = assertIs<EmailLookup.Found>(repository(fake).getEmail("e1")).detail

            assertEquals("visible text", detail.body)
        }

    @Test
    fun `a body cut at maxBodyValueBytes is reported truncated`() =
        runTest {
            val fake = FakeJmapServer()
            fake.emails +=
                FakeEmail(
                    "e1",
                    "e1",
                    setOf("inbox"),
                    "hello",
                    "a@example.test",
                    "2024-01-01T00:00:00Z",
                    body = "a long body cut short",
                    bodyTruncated = true,
                )

            val detail = assertIs<EmailLookup.Found>(repository(fake).getEmail("e1")).detail

            assertEquals(true, detail.bodyTruncated)
        }

    @Test
    fun `a message deleted before it's opened is reported not found`() =
        runTest {
            val fake = FakeJmapServer()

            val lookup = repository(fake).getEmail("gone")

            assertIs<EmailLookup.NotFound>(lookup)
        }

    @Test
    fun `the account identity is fetched once and then remembered`() =
        runTest {
            val fake = FakeJmapServer().withSendableAccount()
            val repository = repository(fake)

            val first = repository.identity()
            val requests = fake.requestCount
            val second = repository.identity()

            assertEquals(SENDABLE_ACCOUNT_EMAIL, first?.email)
            assertEquals(first, second)
            assertEquals(requests, fake.requestCount)
        }

    @Test
    fun `two screens asking for the identity at once share one request`() =
        runTest {
            val fake = FakeJmapServer().withSendableAccount()
            val repository = repository(fake)
            val before = fake.requestCount

            awaitAll(async { repository.identity() }, async { repository.identity() })

            assertEquals(before + 1, fake.requestCount)
        }

    @Test
    fun `a mailbox id is looked up by role once`() =
        runTest {
            val fake = FakeJmapServer()
            fake.mailboxes += FakeMailbox(id = "m2", name = "Inbox", role = "inbox")
            fake.mailboxes += FakeMailbox(id = "m3", name = "Sent", role = "sent")
            val repository = repository(fake)

            assertEquals("m2", repository.mailboxId("inbox"))
            val requests = fake.requestCount
            assertEquals("m3", repository.mailboxId("sent"))
            assertEquals(null, repository.mailboxId("drafts"))

            assertEquals(requests, fake.requestCount)
        }

    @Test
    fun `a proxied image is fetched without credentials`() =
        runTest {
            val fake = FakeJmapServer()
            fake.proxiedImages["abc.sig"] = byteArrayOf(1, 2, 3)
            val repository = MailboxRepository(fake.client(bearerToken = "secret"))

            val bytes = repository.proxiedImage("${repository.imageProxyBase}abc.sig")

            assertEquals(listOf<Byte>(1, 2, 3), bytes?.toList())
            assertEquals(listOf<String?>(null), fake.imageAuthorizations)
        }

    @Test
    fun `an image outside the proxy path is never requested`() =
        runTest {
            val fake = FakeJmapServer()
            val repository = MailboxRepository(fake.client())
            val before = fake.requestCount

            assertNull(repository.proxiedImage("https://cdn.example.test/i/a.png"))
            assertNull(repository.proxiedImage("http://127.0.0.1:9999/jmap/download/u1/b/x"))
            assertEquals(before, fake.requestCount)
        }

    @Test
    fun `a missing or unreachable proxied image is null and leaves the session alone`() =
        runTest {
            val fake = FakeJmapServer()
            val repository = MailboxRepository(fake.client())

            assertNull(repository.proxiedImage("${repository.imageProxyBase}gone"))
            fake.unreachable = true
            assertNull(repository.proxiedImage("${repository.imageProxyBase}gone"))
            fake.unreachable = false
            assertEquals(emptyList(), repository.mailboxes())
        }
}
