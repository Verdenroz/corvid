package com.verdenroz.corvid.feature.mailbox

import com.verdenroz.corvid.core.data.ARCHIVE
import com.verdenroz.corvid.core.data.INBOX
import com.verdenroz.corvid.core.data.JUNK
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.data.TRASH
import com.verdenroz.corvid.core.model.CorvidKeywords
import com.verdenroz.corvid.core.testing.FakeEmail
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.FakeMailbox
import com.verdenroz.corvid.core.testing.boxesOf
import com.verdenroz.corvid.core.testing.client
import com.verdenroz.corvid.core.testing.runViewModelTest
import com.verdenroz.corvid.core.testing.withRoleMailboxes
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.uiPlural
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.action_archived
import com.verdenroz.corvid.feature.mailbox.resources.action_emptied
import com.verdenroz.corvid.feature.mailbox.resources.action_moved
import com.verdenroz.corvid.feature.mailbox.resources.action_reported
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun server() =
    FakeJmapServer().withRoleMailboxes(INBOX, TRASH, ARCHIVE).apply {
        emails +=
            FakeEmail("m1", "m1", setOf(INBOX), "subject", "a@example.test", "2024-01-01T00:00:00Z", keywords = setOf(CorvidKeywords.SEEN))
    }

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

            assertTrue(CorvidKeywords.SEEN !in fake.emails.single().keywords)
            assertNull(actions.notice.value)
        }

    @Test
    fun `reported spam lands in junk and undo puts it back in the inbox`() =
        runViewModelTest {
            val fake = server()
            fake.withRoleMailboxes(JUNK)
            val actions = track(MailActions(MailboxRepository(fake.client())))

            actions.reportSpam(setOf("m1"), conversations = 1)
            val notice = actions.notice.first { it != null }

            assertEquals(uiPlural(Res.plurals.action_reported, 1), notice?.text)
            assertEquals(setOf("junk"), fake.boxesOf("m1"))
            actions.undo()
            actions.notice.first { it?.undo == null && it != null }
            assertEquals(setOf("inbox"), fake.boxesOf("m1"))
        }

    @Test
    fun `moving to a folder names it in the notice and undo puts the conversation back`() =
        runViewModelTest {
            val fake = server()
            fake.mailboxes += FakeMailbox("receipts", "Receipts")
            val actions = track(MailActions(MailboxRepository(fake.client())))

            actions.loadFolders()
            val receipts = actions.folders.first { it != null }!!.single { it.label == "Receipts" }
            actions.moveTo(setOf("m1"), conversations = 1, receipts)
            val notice = actions.notice.first { it != null }

            assertEquals(UiText.Plural(Res.plurals.action_moved, 1, listOf(1, UiText.FromServer("Receipts"))), notice?.text)
            assertEquals(setOf("receipts"), fake.boxesOf("m1"))
            actions.undo()
            actions.notice.first { it?.undo == null && it != null }
            assertEquals(setOf("inbox"), fake.boxesOf("m1"))
        }

    @Test
    fun `move to lists the mailboxes already fetched without asking the server again`() =
        runViewModelTest {
            val fake = server()
            fake.mailboxes += FakeMailbox("receipts", "Receipts")
            val repository = MailboxRepository(fake.client())
            val actions = track(MailActions(repository))
            repository.mailboxes()
            val before = fake.requestCount

            actions.loadFolders()
            val folders = actions.folders.first { it != null }!!

            assertTrue(folders.any { it.label == "Receipts" })
            assertEquals(before, fake.requestCount)
        }

    @Test
    fun `emptying trash deletes everything in it and says how many`() =
        runViewModelTest {
            val fake = server()
            fake.emails.replaceAll { it.copy(mailboxIds = setOf("trash")) }
            fake.emails += FakeEmail("m2", "m2", setOf("trash"), "subject", "b@example.test", "2024-01-02T00:00:00Z")
            val actions = track(MailActions(MailboxRepository(fake.client())))

            actions.empty("trash")
            val notice = actions.notice.first { it != null }

            assertEquals(uiPlural(Res.plurals.action_emptied, 2), notice?.text)
            assertNull(notice?.undo)
            assertTrue(fake.emails.isEmpty())
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
