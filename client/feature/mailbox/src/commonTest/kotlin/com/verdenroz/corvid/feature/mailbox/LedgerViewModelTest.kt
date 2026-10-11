package com.verdenroz.corvid.feature.mailbox

import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.testing.FakeEmail
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.FakeMailbox
import com.verdenroz.corvid.core.testing.client
import com.verdenroz.corvid.core.testing.runViewModelTest
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.mailbox_no_mailbox
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

private const val EMAIL_COUNT = 60

class LedgerViewModelTest {
    private suspend fun repository(fake: FakeJmapServer): MailboxRepository {
        val client = fake.client()
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
            assertEquals(50, firstPage.conversations.size)
            assertEquals("e49", firstPage.conversations.last().latest.id)

            // e10 leaves the mailbox mid-scroll; a real server would bump queryState
            // for exactly this reason.
            fake.emails.removeAll { it.id == "e10" }
            fake.queryState = "qs2"
            vm.loadMore()

            val restarted =
                vm.state.first { it is LedgerUiState.Loaded && it.conversations.none { c -> c.latest.id == "e10" } } as LedgerUiState.Loaded
            assertEquals(50, restarted.conversations.size)
            assertEquals("e0", restarted.conversations.first().latest.id)
            // e50 only enters the top 50 once e10's removal shifts everything after it up:
            // seeing it here proves this is a fresh fetch, not page one plus a stale appendix.
            assertTrue(restarted.conversations.any { it.latest.id == "e50" })
        }

    @Test
    fun `scrolling past the end twice while a page loads fetches that page once`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            fake.mailboxes += FakeMailbox(id = "inbox", name = "Inbox", role = "inbox")
            repeat(2 * EMAIL_COUNT) { i ->
                val receivedAt = Instant.fromEpochSeconds((1000 - i).toLong()).toString()
                fake.emails += FakeEmail("e$i", "e$i", setOf("inbox"), "subject $i", "s$i@x.test", receivedAt)
            }
            var queries = 0
            fake.beforeEmailQuery = { queries++ }
            val vm = track(LedgerViewModel(repository(fake), LedgerTab.Inbox))
            vm.state.first { it is LedgerUiState.Loaded }

            vm.loadMore()
            vm.loadMore()
            val second = vm.state.first { it is LedgerUiState.Loaded && it.conversations.size > 50 } as LedgerUiState.Loaded
            withContext(Dispatchers.Default) { delay(300) }

            assertEquals(2, queries)
            val listed = (vm.state.value as LedgerUiState.Loaded).conversations.map { it.latest.id }
            assertEquals(second.conversations.map { it.latest.id }.distinct(), listed)
        }

    @Test
    fun `a keyword written elsewhere in the app shows on the row already listed`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            fake.mailboxes += FakeMailbox(id = "inbox", name = "Inbox", role = "inbox")
            val guessed = setOf("corvid-llm-label-bills")
            fake.emails += FakeEmail("m1", "m1", setOf("inbox"), "s", "a@x.test", "2024-01-02T00:00:00Z", keywords = guessed)
            val repository = repository(fake)
            val vm = track(LedgerViewModel(repository, LedgerTab.Inbox))
            vm.state.first { it is LedgerUiState.Loaded }

            repository.updateKeywords("m1", mapOf("bills" to true, "corvid-llm-label-bills" to false))

            val patched = vm.state.first { (it as? LedgerUiState.Loaded)?.conversations?.single()?.latest?.keywords == setOf("bills") }
            assertEquals(1, (patched as LedgerUiState.Loaded).conversations.size)
        }

    @Test
    fun `refresh reloads from the top and completes once the new page is shown`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            fake.mailboxes += FakeMailbox(id = "inbox", name = "Inbox", role = "inbox")
            val vm = track(LedgerViewModel(repository(fake), LedgerTab.Inbox))
            vm.state.first { it is LedgerUiState.Loaded }
            fake.emails += FakeEmail("m1", "m1", setOf("inbox"), "s", "a@x.test", "2024-01-02T00:00:00Z")

            vm.refresh()?.join()

            assertEquals(listOf("m1"), (vm.state.value as LedgerUiState.Loaded).conversations.map { it.latest.id })
        }

    @Test
    fun `refresh does nothing before the first page has loaded`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            fake.mailboxes += FakeMailbox(id = "inbox", name = "Inbox", role = "inbox")
            val gate = CompletableDeferred<Unit>()
            fake.gate = gate
            val vm = track(LedgerViewModel(repository(fake), LedgerTab.Inbox))

            assertNull(vm.refresh())
            gate.complete(Unit)
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

            assertEquals(listOf("in", "out"), loaded.conversations.map { it.latest.id })
        }

    @Test
    fun `a tab whose mailbox the account lacks says which one`() =
        runViewModelTest {
            val vm = track(LedgerViewModel(repository(FakeJmapServer()), LedgerTab.Drafts))

            val failed = vm.state.first { it is LedgerUiState.Failed } as LedgerUiState.Failed

            assertEquals(uiText(Res.string.mailbox_no_mailbox, "drafts"), failed.message)
        }

    @Test
    fun `a search lists matches from every mailbox newest first`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            fake.emails += FakeEmail("a", "a", setOf("inbox"), "Invoice March", "a@x.test", "2024-01-01T00:00:00Z")
            fake.emails += FakeEmail("b", "b", setOf("archive"), "Re: invoice", "b@x.test", "2024-01-02T00:00:00Z")
            fake.emails += FakeEmail("c", "c", setOf("inbox"), "Lunch", "c@x.test", "2024-01-03T00:00:00Z")
            val vm = track(LedgerViewModel(repository(fake), LedgerSource.Search("invoice")))

            val loaded = vm.state.first { it is LedgerUiState.Loaded } as LedgerUiState.Loaded

            assertEquals(listOf("b", "a"), loaded.conversations.map { it.latest.id })
        }

    @Test
    fun `a search that matches nothing is an empty loaded list`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            fake.emails += FakeEmail("a", "a", setOf("inbox"), "Lunch", "a@x.test", "2024-01-01T00:00:00Z")
            val vm = track(LedgerViewModel(repository(fake), LedgerSource.Search("invoice")))

            val loaded = vm.state.first { it is LedgerUiState.Loaded } as LedgerUiState.Loaded

            assertTrue(loaded.conversations.isEmpty())
        }

    @Test
    fun `a search the server refuses fails like a tab does`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            val repository = repository(fake)
            fake.apiProblem = "urn:ietf:params:jmap:error:limit"
            val vm = track(LedgerViewModel(repository, LedgerSource.Search("invoice")))

            vm.state.first { it is LedgerUiState.Failed }
        }

    @Test
    fun `refreshing a loaded ledger picks up a message that arrived since`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            fake.mailboxes += FakeMailbox(id = "drafts", name = "Drafts", role = "drafts")
            val vm = track(LedgerViewModel(repository(fake), LedgerTab.Drafts))
            vm.state.first { it is LedgerUiState.Loaded }

            fake.emails +=
                FakeEmail(
                    id = "d1",
                    threadId = "d1",
                    mailboxIds = setOf("drafts"),
                    subject = "half written",
                    from = "me@example.test",
                    receivedAt = Instant.fromEpochSeconds(1).toString(),
                )
            vm.refresh()

            val loaded = vm.state.first { it is LedgerUiState.Loaded && it.conversations.isNotEmpty() } as LedgerUiState.Loaded
            assertEquals(listOf("d1"), loaded.conversations.map { it.latest.id })
        }

    @Test
    fun `drafts of one thread are listed one by one, each opening in compose`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            fake.mailboxes += FakeMailbox(id = "drafts", name = "Drafts", role = "drafts")
            fake.emails += FakeEmail("d1", "t", setOf("drafts"), "s", "me@x.test", "2024-01-01T00:00:00Z")
            fake.emails += FakeEmail("d2", "t", setOf("drafts"), "s", "me@x.test", "2024-01-02T00:00:00Z")
            val vm = track(LedgerViewModel(repository(fake), LedgerTab.Drafts))

            val loaded = vm.state.first { it is LedgerUiState.Loaded } as LedgerUiState.Loaded

            assertEquals(listOf("d2", "d1"), loaded.conversations.map { it.latest.id })
            assertEquals(listOf(1, 1), loaded.conversations.map { it.messageCount })
        }

    @Test
    fun `a thread is one row with its message count and unread state`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            fake.mailboxes += FakeMailbox(id = "inbox", name = "Inbox", role = "inbox")
            fake.emails += FakeEmail("a1", "a", setOf("inbox"), "s", "a@x.test", "2024-01-01T00:00:00Z")
            fake.emails += FakeEmail("a2", "a", setOf("inbox"), "s", "a@x.test", "2024-01-02T00:00:00Z", keywords = setOf("\$seen"))
            fake.emails += FakeEmail("b", "b", setOf("inbox"), "s", "b@x.test", "2024-01-03T00:00:00Z", keywords = setOf("\$seen"))
            val vm = track(LedgerViewModel(repository(fake), LedgerTab.Inbox))

            val loaded = vm.state.first { it is LedgerUiState.Loaded } as LedgerUiState.Loaded

            assertEquals(listOf("b", "a2"), loaded.conversations.map { it.latest.id })
            assertEquals(listOf(1, 2), loaded.conversations.map { it.messageCount })
            assertEquals(listOf(false, true), loaded.conversations.map { it.unread })
        }

    @Test
    fun `reading an older message of a thread clears the row's unread state`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            fake.mailboxes += FakeMailbox(id = "inbox", name = "Inbox", role = "inbox")
            fake.emails += FakeEmail("a1", "a", setOf("inbox"), "s", "a@x.test", "2024-01-01T00:00:00Z")
            fake.emails += FakeEmail("a2", "a", setOf("inbox"), "s", "a@x.test", "2024-01-02T00:00:00Z", keywords = setOf("\$seen"))
            val repository = repository(fake)
            val vm = track(LedgerViewModel(repository, LedgerTab.Inbox))
            vm.state.first { it is LedgerUiState.Loaded }

            repository.updateKeywords("a1", mapOf("\$seen" to true))

            vm.state.first { (it as? LedgerUiState.Loaded)?.conversations?.single()?.unread == false }
        }

    @Test
    fun `select all ticks every loaded conversation and a refetch drops ticks for rows that left`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            fake.mailboxes += FakeMailbox(id = "inbox", name = "Inbox", role = "inbox")
            listOf("a", "b").forEach { id ->
                fake.emails += FakeEmail(id, id, setOf("inbox"), "subject", "$id@example.test", "2024-01-01T00:00:00Z")
            }
            val vm = track(LedgerViewModel(repository(fake), LedgerTab.Inbox))
            vm.state.first { it is LedgerUiState.Loaded }

            vm.selectAll()
            assertEquals(setOf("a", "b"), vm.selected.value)
            fake.emails.removeAll { it.id == "a" }
            vm.refresh()?.join()

            assertEquals(setOf("b"), vm.selected.value)
        }

    @Test
    fun `toggling a ticked row unticks it`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            fake.mailboxes += FakeMailbox(id = "inbox", name = "Inbox", role = "inbox")
            val vm = track(LedgerViewModel(repository(fake), LedgerTab.Inbox))

            vm.toggleSelected("a")
            vm.toggleSelected("a")

            assertTrue(vm.selected.value.isEmpty())
        }

    @Test
    fun `a row moving away is hidden while the move runs and gone after the refetch`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            fake.mailboxes += FakeMailbox(id = "inbox", name = "Inbox", role = "inbox")
            fake.mailboxes += FakeMailbox(id = "trash", name = "Trash", role = "trash")
            listOf("a", "b").forEach { id ->
                fake.emails += FakeEmail(id, id, setOf("inbox"), "subject", "$id@example.test", "2024-01-01T00:00:00Z")
            }
            val gate = CompletableDeferred<Unit>()
            val repository = repository(fake)
            val vm = track(LedgerViewModel(repository, LedgerTab.Inbox))
            vm.state.first { it is LedgerUiState.Loaded }

            fake.gate = gate
            coroutineScope {
                launch { repository.trash(listOf("a")) }
                val during = vm.state.first { (it as? LedgerUiState.Loaded)?.conversations?.size == 1 } as LedgerUiState.Loaded
                assertEquals(listOf("b"), during.conversations.map { it.latest.id })
                fake.gate = null
                gate.complete(Unit)
            }

            assertEquals(setOf("trash"), fake.emails.single { it.id == "a" }.mailboxIds)
            vm.state.first { (it as? LedgerUiState.Loaded)?.conversations?.map { c -> c.latest.id } == listOf("b") }
        }

    @Test
    fun `an archive the account has not made yet lists nothing, then lists what the first archive filed`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            fake.mailboxes += FakeMailbox(id = "inbox", name = "Inbox", role = "inbox")
            fake.emails += FakeEmail("a", "a", setOf("inbox"), "subject", "a@example.test", "2024-01-01T00:00:00Z")
            val repository = repository(fake)
            val vm = track(LedgerViewModel(repository, LedgerTab.Archive))

            assertEquals(LedgerUiState.Loaded(emptyList(), canLoadMore = false), vm.state.first { it !is LedgerUiState.Loading })
            repository.archive(listOf("a"))
            vm.refresh()?.join()

            assertEquals(listOf("a"), (vm.state.value as LedgerUiState.Loaded).conversations.map { it.latest.id })
        }

    @Test
    fun `junk the account lacks is said plainly`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            fake.mailboxes += FakeMailbox(id = "inbox", name = "Inbox", role = "inbox")
            val vm = track(LedgerViewModel(repository(fake), LedgerTab.Junk))

            assertEquals(
                LedgerUiState.Failed(uiText(Res.string.mailbox_no_mailbox, "junk")),
                vm.state.first { it !is LedgerUiState.Loading },
            )
        }
}
