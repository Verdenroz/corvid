package com.verdenroz.corvid.feature.mailbox

import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.jmap.AuthLatch
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.createJmapHttpClient
import com.verdenroz.corvid.core.testing.FakeEmail
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.FakeMailbox
import com.verdenroz.corvid.core.testing.runViewModelTest
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

private const val BASE_URL = "http://127.0.0.1:9999"
private const val EMAIL_COUNT = 60

class InboxViewModelTest {
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
            val vm = track(InboxViewModel(repository(fake)))

            val firstPage = vm.state.first { it is InboxUiState.Loaded } as InboxUiState.Loaded
            assertEquals(50, firstPage.emails.size)
            assertEquals("e49", firstPage.emails.last().id)

            // e10 leaves the mailbox mid-scroll; a real server would bump queryState
            // for exactly this reason.
            fake.emails.removeAll { it.id == "e10" }
            fake.queryState = "qs2"
            vm.loadMore()

            val restarted =
                vm.state.first { it is InboxUiState.Loaded && it.emails.none { email -> email.id == "e10" } } as InboxUiState.Loaded
            assertEquals(50, restarted.emails.size)
            assertEquals("e0", restarted.emails.first().id)
            // e50 only enters the top 50 once e10's removal shifts everything after it up:
            // seeing it here proves this is a fresh fetch, not page one plus a stale appendix.
            assertTrue(restarted.emails.any { it.id == "e50" })
        }
}
