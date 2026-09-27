package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.jmap.AuthLatch
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.createJmapHttpClient
import com.verdenroz.corvid.core.model.EmailSummary
import com.verdenroz.corvid.core.testing.FakeEmail
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.FakeMailbox
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

private const val BASE_URL = "http://127.0.0.1:9999"
private const val EMAIL_COUNT = 170

private suspend fun repository(fake: FakeJmapServer): MailboxRepository {
    val client = JmapClient.connect(createJmapHttpClient(fake.engine), AuthLatch(), BASE_URL)
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
            val repository = repository(fake)

            val all = mutableListOf<EmailSummary>()
            var anchor: String? = null
            do {
                val page = assertIs<EmailPage.Found>(repository.page("inbox", anchor))
                all += page.emails
                anchor = page.emails.lastOrNull()?.id
            } while (page.emails.isNotEmpty())

            assertEquals(EMAIL_COUNT, all.size)
            assertEquals(all.map { it.id }.toSet().size, all.size)
        }

    @Test
    fun `an anchor the server no longer recognises restarts the query from the top`() =
        runTest {
            val fake = FakeJmapServer()
            fake.emails += FakeEmail("e1", "e1", setOf("inbox"), "s1", "a@example.test", "2024-01-01T00:00:00Z")

            val page = repository(fake).page("inbox", anchor = "does-not-exist")

            assertIs<EmailPage.AnchorNotFound>(page)
        }
}
