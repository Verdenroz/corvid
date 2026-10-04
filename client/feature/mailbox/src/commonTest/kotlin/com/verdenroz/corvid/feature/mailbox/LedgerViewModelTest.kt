package com.verdenroz.corvid.feature.mailbox

import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.jmap.AuthLatch
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.createJmapHttpClient
import com.verdenroz.corvid.core.testing.FakeEmail
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.FakeMailbox
import com.verdenroz.corvid.core.testing.runViewModelTest
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.mailbox_no_mailbox
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

private const val BASE_URL = "http://127.0.0.1:9999"
private const val EMAIL_COUNT = 60

class LedgerViewModelTest {
    private suspend fun repository(fake: FakeJmapServer): MailboxRepository {
        val client = JmapClient.connect(createJmapHttpClient(fake.engine), AuthLatch(), BASE_URL)
        return MailboxRepository(client)
    }

    @Test
    fun `a message moved out of the Inbox mid-scroll restarts the list`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            fake.mailboxes += FakeMailbox(id = "inbox", name = "Inbox", role = "inbox")
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
            val vm = track(LedgerViewModel(repository(fake), LedgerTab.Inbox))

            val firstPage = vm.state.first { it is LedgerUiState.Loaded } as LedgerUiState.Loaded
            assertEquals(50, firstPage.emails.size)
            assertEquals("e49", firstPage.emails.last().id)

            // e10 leaves the mailbox mid-scroll; a real server would bump queryState
            // for exactly this reason.
            fake.emails.removeAll { it.id == "e10" }
            fake.queryState = "qs2"
            vm.loadMore()

            val restarted =
                vm.state.first { it is LedgerUiState.Loaded && it.emails.none { email -> email.id == "e10" } } as LedgerUiState.Loaded
            assertEquals(50, restarted.emails.size)
            assertEquals("e0", restarted.emails.first().id)
            // e50 only enters the top 50 once e10's removal shifts everything after it up:
            // seeing it here proves this is a fresh fetch, not page one plus a stale appendix.
            assertTrue(restarted.emails.any { it.id == "e50" })
        }

    @Test
    fun `the starred tab lists flagged messages from every mailbox`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            fake.mailboxes += FakeMailbox(id = "inbox", name = "Inbox", role = "inbox")
            fake.mailboxes += FakeMailbox(id = "sent", name = "Sent", role = "sent")
            fake.emails += FakeEmail("in", "in", setOf("inbox"), "s", "a@x.test", "2024-01-02T00:00:00Z", keywords = setOf("\$flagged"))
            fake.emails += FakeEmail("out", "out", setOf("sent"), "s", "me@x.test", "2024-01-01T00:00:00Z", keywords = setOf("\$flagged"))
            fake.emails += FakeEmail("plain", "plain", setOf("inbox"), "s", "b@x.test", "2024-01-03T00:00:00Z")
            val vm = track(LedgerViewModel(repository(fake), LedgerTab.Starred))

            val loaded = vm.state.first { it is LedgerUiState.Loaded } as LedgerUiState.Loaded

            assertEquals(listOf("in", "out"), loaded.emails.map { it.id })
        }

    @Test
    fun `a tab whose mailbox the account lacks says which one`() =
        runViewModelTest {
            val vm = track(LedgerViewModel(repository(FakeJmapServer()), LedgerTab.Drafts))

            val failed = vm.state.first { it is LedgerUiState.Failed } as LedgerUiState.Failed

            assertEquals(uiText(Res.string.mailbox_no_mailbox, "drafts"), failed.message)
        }
}
