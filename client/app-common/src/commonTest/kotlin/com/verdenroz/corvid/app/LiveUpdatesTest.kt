package com.verdenroz.corvid.app

import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.jmap.StateChange
import com.verdenroz.corvid.core.testing.FakeEmail
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.FakeMailbox
import com.verdenroz.corvid.core.testing.client
import com.verdenroz.corvid.core.testing.runViewModelTest
import com.verdenroz.corvid.feature.mailbox.LedgerTab
import com.verdenroz.corvid.feature.mailbox.LedgerUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

private fun change(vararg types: String) = StateChange(mapOf("u1" to types.associateWith { "s2" }))

@OptIn(ExperimentalCoroutinesApi::class)
class LiveUpdatesTest {
    @Test
    fun `a burst of mail changes runs the callback once`() =
        runTest {
            val changes = MutableSharedFlow<StateChange>()
            var runs = 0
            val job = launch { followMailboxChanges(changes, 500.milliseconds) { runs++ } }
            runCurrent()

            changes.emit(change("Email"))
            advanceTimeBy(200.milliseconds)
            changes.emit(change("Thread", "Mailbox"))
            advanceTimeBy(499.milliseconds)
            assertEquals(0, runs)
            advanceTimeBy(2.milliseconds)

            assertEquals(1, runs)
            job.cancel()
        }

    @Test
    fun `a change to a type the app does not show runs nothing`() =
        runTest {
            val changes = MutableSharedFlow<StateChange>()
            var runs = 0
            val job = launch { followMailboxChanges(changes, 500.milliseconds) { runs++ } }
            runCurrent()

            changes.emit(change("Identity", "EmailSubmission"))
            advanceTimeBy(5_000.milliseconds)

            assertEquals(0, runs)
            job.cancel()
        }

    @Test
    fun `a mail change shows a new message in an open ledger`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            fake.mailboxes += FakeMailbox(id = "inbox", name = "Inbox", role = "inbox")
            val ledgers = Ledgers(MailboxRepository(fake.client()))
            val inbox = track(ledgers.of(LedgerTab.Inbox))
            inbox.state.first { it is LedgerUiState.Loaded }
            fake.emails +=
                FakeEmail(
                    id = "new",
                    threadId = "new",
                    mailboxIds = setOf("inbox"),
                    subject = "just arrived",
                    from = "friend@example.test",
                    receivedAt = Instant.fromEpochSeconds(1).toString(),
                )
            fake.queryState = "qs2"
            val changes = MutableSharedFlow<StateChange>()
            coroutineScope {
                val job = launch { followMailboxChanges(changes, 0.milliseconds) { ledgers.refreshAll() } }

                changes.subscriptionCount.first { it > 0 }
                changes.emit(change("Email"))

                val loaded = inbox.state.first { it is LedgerUiState.Loaded && it.conversations.isNotEmpty() } as LedgerUiState.Loaded
                assertEquals("new", loaded.conversations.single().latest.id)
                job.cancel()
            }
        }
}
