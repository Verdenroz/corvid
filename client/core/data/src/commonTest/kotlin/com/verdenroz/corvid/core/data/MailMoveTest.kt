package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.model.HeaderField
import com.verdenroz.corvid.core.testing.FakeEmail
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.FakeMailbox
import com.verdenroz.corvid.core.testing.client
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun message(
    id: String,
    vararg mailboxes: String,
) = FakeEmail(id, id, mailboxes.toSet(), "subject", "$id@example.test", "2024-01-01T00:00:00Z")

private fun server(vararg roles: String) =
    FakeJmapServer().apply {
        mailboxes += FakeMailbox("inbox", "Inbox", "inbox")
        roles.forEach { mailboxes += FakeMailbox(it, it, it) }
    }

private fun FakeJmapServer.boxesOf(id: String) = emails.single { it.id == id }.mailboxIds

class MailMoveTest {
    @Test
    fun `archiving takes a message out of the inbox and keeps its other mailboxes`() =
        runTest {
            val fake = server("archive")
            fake.mailboxes += FakeMailbox("work", "Work")
            fake.emails += message("m1", "inbox", "work")

            val move = MailboxRepository(fake.client()).archive(listOf("m1"))

            assertEquals(setOf("work"), fake.boxesOf("m1"))
            assertEquals(setOf("m1"), move.moved)
        }

    @Test
    fun `archiving a message filed only in the inbox puts it in the archive mailbox`() =
        runTest {
            val fake = server("archive")
            fake.emails += message("m1", "inbox")

            MailboxRepository(fake.client()).archive(listOf("m1"))

            assertEquals(setOf("archive"), fake.boxesOf("m1"))
        }

    @Test
    fun `archiving with no archive mailbox makes one with the archive role`() =
        runTest {
            val fake = server()
            fake.emails += message("m1", "inbox")

            MailboxRepository(fake.client()).archive(listOf("m1"))

            val made = fake.mailboxes.single { it.role == "archive" }
            assertEquals(setOf(made.id), fake.boxesOf("m1"))
        }

    @Test
    fun `archiving leaves a message outside the inbox where it is`() =
        runTest {
            val fake = server("archive", "sent")
            fake.emails += message("m1", "sent")

            val move = MailboxRepository(fake.client()).archive(listOf("m1"))

            assertEquals(setOf("sent"), fake.boxesOf("m1"))
            assertTrue(move.moved.isEmpty())
        }

    @Test
    fun `trashing files a message in trash alone`() =
        runTest {
            val fake = server("trash")
            fake.mailboxes += FakeMailbox("work", "Work")
            fake.emails += message("m1", "inbox", "work")

            MailboxRepository(fake.client()).trash(listOf("m1"))

            assertEquals(setOf("trash"), fake.boxesOf("m1"))
        }

    @Test
    fun `trashing with no trash mailbox refuses every message and moves none`() =
        runTest {
            val fake = server()
            fake.emails += message("m1", "inbox")

            val move = MailboxRepository(fake.client()).trash(listOf("m1"))

            assertEquals(setOf("inbox"), fake.boxesOf("m1"))
            assertEquals(setOf("m1"), move.refused.keys)
        }

    @Test
    fun `undoing a move puts each message back in exactly the mailboxes it left`() =
        runTest {
            val fake = server("trash")
            fake.mailboxes += FakeMailbox("work", "Work")
            fake.emails += message("m1", "inbox", "work")
            fake.emails += message("m2", "inbox")
            val repository = MailboxRepository(fake.client())

            repository.undo(repository.trash(listOf("m1", "m2")))

            assertEquals(setOf("inbox", "work"), fake.boxesOf("m1"))
            assertEquals(setOf("inbox"), fake.boxesOf("m2"))
        }

    @Test
    fun `a move patches only the mailboxes that change`() =
        runTest {
            val fake = server("archive")
            fake.mailboxes += FakeMailbox("work", "Work")
            fake.emails += message("m1", "inbox", "work")

            MailboxRepository(fake.client()).archive(listOf("m1"))

            assertEquals(setOf("mailboxIds/inbox"), fake.emailPatches.single().second.keys)
        }

    @Test
    fun `destroying deletes the message from the server`() =
        runTest {
            val fake = server("trash")
            fake.emails += message("m1", "trash")

            val refused = MailboxRepository(fake.client()).destroy(listOf("m1"))

            assertTrue(refused.isEmpty())
            assertTrue(fake.emails.isEmpty())
        }

    @Test
    fun `destroying spares a message of the thread filed outside trash`() =
        runTest {
            val fake = server("trash")
            fake.emails += message("m1", "trash")
            fake.emails += message("m2", "inbox", "trash")

            MailboxRepository(fake.client()).destroy(listOf("m1", "m2"))

            assertEquals(listOf("m2"), fake.emails.map { it.id })
        }

    @Test
    fun `headers come back unfolded and in order`() =
        runTest {
            val fake = server()
            fake.emails +=
                message("m1", "inbox").copy(
                    headers = mapOf("Received" to listOf("from a\r\n\tby b"), "Subject" to listOf("hello")),
                )

            val headers = MailboxRepository(fake.client()).headers("m1")

            assertEquals(listOf(HeaderField("Received", "from a\tby b"), HeaderField("Subject", "hello")), headers)
        }

    @Test
    fun `headers of a message that is gone are null`() =
        runTest {
            assertNull(MailboxRepository(server().client()).headers("missing"))
        }
}
