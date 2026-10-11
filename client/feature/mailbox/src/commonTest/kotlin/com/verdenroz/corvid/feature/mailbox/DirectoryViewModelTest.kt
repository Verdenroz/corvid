package com.verdenroz.corvid.feature.mailbox

import com.verdenroz.corvid.core.data.INBOX
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.testing.FakeEmail
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.client
import com.verdenroz.corvid.core.testing.runViewModelTest
import com.verdenroz.corvid.core.testing.withRoleMailboxes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals

private fun email(
    id: String,
    from: String,
) = FakeEmail(id, id, setOf(INBOX), id, from, "2026-10-01T08:00:00Z")

private suspend fun DirectoryViewModel.people(count: Int) =
    (state.first { it is DirectoryUiState.Loaded && it.directory.people.size == count } as DirectoryUiState.Loaded).directory.people

class DirectoryViewModelTest {
    @Test
    fun `nothing is fetched until a directory screen is shown`() =
        runViewModelTest {
            val fake = FakeJmapServer().withRoleMailboxes(INBOX)
            fake.emails += email("a", "ada@friends.test")
            val directory = track(DirectoryViewModel(MailboxRepository(fake.client())))
            repeat(5) { yield() }
            fake.emails += email("b", "bo@friends.test")

            directory.shown(true)

            assertEquals(2, directory.people(2).size)
        }

    @Test
    fun `a mail change off screen refetches when a directory screen is next shown`() =
        runViewModelTest {
            val fake = FakeJmapServer().withRoleMailboxes(INBOX)
            fake.emails += email("a", "ada@friends.test")
            val directory = track(DirectoryViewModel(MailboxRepository(fake.client())))
            directory.shown(true)
            directory.people(1)
            directory.shown(false)
            fake.emails += email("b", "bo@friends.test")

            directory.mailChanged()
            repeat(5) { yield() }
            assertEquals(1, (directory.state.value as DirectoryUiState.Loaded).directory.people.size)
            directory.shown(true)

            assertEquals(2, directory.people(2).size)
        }

    @Test
    fun `a failed fetch is tried again the next time a directory screen is shown`() =
        runViewModelTest {
            val fake = FakeJmapServer().withRoleMailboxes(INBOX)
            fake.emails += email("a", "ada@friends.test")
            val directory = track(DirectoryViewModel(MailboxRepository(fake.client())))
            fake.unreachable = true
            directory.shown(true)
            directory.state.first { it is DirectoryUiState.Failed }
            directory.shown(false)
            fake.unreachable = false

            directory.shown(true)

            assertEquals(1, directory.people(1).size)
        }
}
