package com.verdenroz.corvid.app

import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.testing.FakeEmail
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.FakeMailbox
import com.verdenroz.corvid.core.testing.client
import com.verdenroz.corvid.core.testing.runViewModelTest
import com.verdenroz.corvid.feature.mailbox.LedgerTab
import com.verdenroz.corvid.feature.mailbox.LedgerUiState
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals

class LedgersTest {
    private val fake =
        FakeJmapServer().apply {
            mailboxes += FakeMailbox(id = "inbox", name = "Inbox", role = "inbox")
            emails += FakeEmail("a", "a", setOf("inbox"), "invoice", "ada@example.test", "2024-01-01T00:00:00Z")
        }

    @Test
    fun `opening a message drops the ticks of a search as well as a tab`() =
        runViewModelTest {
            val ledgers = Ledgers(MailboxRepository(fake.client()))
            val models = EntryModels()
            val nav = navigator(RecordingHolder(), Route.Search("invoice"), onLeave = ledgers::leave)
            val search = ledgers.search(models, nav.entryKey(1), "invoice")
            val inbox = ledgers.of(LedgerTab.Inbox)
            search.state.first { it is LedgerUiState.Loaded }
            inbox.state.first { it is LedgerUiState.Loaded }
            search.toggleSelected("a")
            inbox.toggleSelected("a")

            nav.push(Route.Reader("a"))

            assertEquals(emptySet(), search.selected.value)
            assertEquals(emptySet(), inbox.selected.value)
            models.clear()
            ledgers.clear()
        }

    @Test
    fun `a popped search leaves the registry`() =
        runViewModelTest {
            val ledgers = Ledgers(MailboxRepository(fake.client()))
            val models = EntryModels()
            val search = ledgers.search(models, "1 search", "invoice")
            search.state.first { it is LedgerUiState.Loaded }

            models.retainOnly(emptySet())
            search.toggleSelected("a")
            ledgers.leave()

            assertEquals(setOf("a"), search.selected.value)
            ledgers.clear()
        }
}
