package com.verdenroz.corvid.feature.mailbox

import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.testing.FakeEmail
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.FakeMailbox
import com.verdenroz.corvid.core.testing.client
import com.verdenroz.corvid.core.testing.runViewModelTest
import com.verdenroz.corvid.core.text.uiPlural
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.action_archived
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun server() =
    FakeJmapServer().apply {
        mailboxes += FakeMailbox("inbox", "Inbox", "inbox")
        mailboxes += FakeMailbox("trash", "Trash", "trash")
        mailboxes += FakeMailbox("archive", "Archive", "archive")
        emails += FakeEmail("m1", "m1", setOf("inbox"), "subject", "a@example.test", "2024-01-01T00:00:00Z", keywords = setOf("\$seen"))
    }

private fun FakeJmapServer.boxesOf(id: String) = emails.single { it.id == id }.mailboxIds

class MailActionsTest {
    @Test
    fun `an archived conversation offers undo, which puts it back in the inbox`() =
        runViewModelTest {
            val fake = server()
            val actions = track(MailActions(MailboxRepository(fake.client())))

            actions.archive(setOf("m1"), conversations = 1)
            val notice = actions.notice.first { it != null }

            assertEquals(uiPlural(Res.plurals.action_archived, 1), notice?.text)
            assertEquals(setOf("archive"), fake.boxesOf("m1"))
            actions.undo()
            actions.notice.first { it?.undo == null && it != null }
            assertEquals(setOf("inbox"), fake.boxesOf("m1"))
        }

    @Test
    fun `deleting for good offers no undo`() =
        runViewModelTest {
            val fake = server()
            fake.emails.replaceAll { it.copy(mailboxIds = setOf("trash")) }
            val actions = track(MailActions(MailboxRepository(fake.client())))

            actions.destroy(setOf("m1"), conversations = 1)
            val notice = actions.notice.first { it != null }

            assertNull(notice?.undo)
            assertTrue(fake.emails.isEmpty())
        }

    @Test
    fun `marking unread clears the seen flag and says nothing when it works`() =
        runViewModelTest {
            val fake = server()
            val repository = MailboxRepository(fake.client())
            val actions = track(MailActions(repository))

            coroutineScope {
                val patched = async { repository.keywordPatches.first() }
                actions.setRead(setOf("m1"), read = false)
                patched.await()
            }

            assertTrue("\$seen" !in fake.emails.single().keywords)
            assertNull(actions.notice.value)
        }

    @Test
    fun `a trash the server lacks is said plainly rather than shown as done`() =
        runViewModelTest {
            val fake = server()
            fake.mailboxes.removeAll { it.role == "trash" }
            val actions = track(MailActions(MailboxRepository(fake.client())))

            actions.trash(setOf("m1"), conversations = 1)
            val notice = actions.notice.first { it != null }

            assertNotNull(notice)
            assertNull(notice.undo)
            assertEquals(setOf("inbox"), fake.boxesOf("m1"))
        }
}
