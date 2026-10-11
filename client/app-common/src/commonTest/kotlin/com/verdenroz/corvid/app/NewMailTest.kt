package com.verdenroz.corvid.app

import com.verdenroz.corvid.core.data.ARCHIVE
import com.verdenroz.corvid.core.data.INBOX
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.testing.FakeEmail
import com.verdenroz.corvid.core.testing.FakeIdentity
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.KeyedTextResolver
import com.verdenroz.corvid.core.testing.client
import com.verdenroz.corvid.core.testing.withRoleMailboxes
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

private const val OWN = "me@corvid.test"

private class RecordingNewMailNotifier : NewMailNotifier {
    val shown = mutableListOf<Pair<String, String>>()
    var clears = 0

    override fun post(
        title: String,
        text: String,
    ) {
        shown += title to text
    }

    override fun clear() {
        clears++
    }
}

private class MailRig(val fake: FakeJmapServer, val notifier: RecordingNewMailNotifier, val newMail: NewMail) {
    fun arrive(
        id: String,
        atSecond: Long,
        from: String = "friend@example.test",
        mailbox: String = "inbox",
        keywords: Set<String> = emptySet(),
    ) {
        fake.emails +=
            FakeEmail(
                id = id,
                threadId = id,
                mailboxIds = setOf(mailbox),
                subject = "subject of $id",
                from = from,
                receivedAt = Instant.fromEpochSeconds(atSecond).toString(),
                keywords = keywords,
                preview = "preview of $id",
            )
    }

    fun moveToInbox(id: String) {
        fake.emails.replaceAll { if (it.id == id) it.copy(mailboxIds = setOf("inbox")) else it }
    }
}

private suspend fun mailRig(): MailRig {
    val fake = FakeJmapServer()
    fake.withRoleMailboxes(INBOX, ARCHIVE)
    fake.identities += FakeIdentity(id = "i1", email = OWN)
    val notifier = RecordingNewMailNotifier()
    val newMail = NewMail(MailboxRepository(fake.client())::inboxArrivals, notifier, KeyedTextResolver)
    return MailRig(fake, notifier, newMail)
}

/** The rig's Inbox is off screen and notifications are on, with [existing] already in the Inbox before the first check. */
private suspend fun MailRig.started(vararg existing: Pair<String, Long>): MailRig {
    existing.forEach { (id, at) -> arrive(id, at) }
    newMail.attention(inboxInView = false, enabled = true)
    newMail.check()
    return this
}

class NewMailTest {
    @Test
    fun `unread mail already in the inbox at the first check is not new`() =
        runTest {
            val rig = mailRig().started("old1" to 10, "old2" to 20)

            rig.newMail.check()

            assertEquals(emptyList(), rig.notifier.shown)
        }

    @Test
    fun `an unread arrival after the first check posts new mail with a count of one`() =
        runTest {
            val rig = mailRig().started("old" to 10)

            rig.arrive("new", 30)
            rig.newMail.check()

            assertEquals(listOf("new_mail_title" to "new_mail_count[1]"), rig.notifier.shown)
        }

    @Test
    fun `the notification names no sender, subject or preview`() =
        runTest {
            val rig = mailRig().started()

            rig.arrive("new", 30, from = "alice@example.test")
            rig.newMail.check()

            val (title, text) = rig.notifier.shown.single()
            listOf("alice", "subject", "preview").forEach { leak -> assertTrue(leak !in title + text, "notification carries $leak") }
        }

    @Test
    fun `a burst across checks updates one count instead of adding notifications`() =
        runTest {
            val rig = mailRig().started("old" to 10)

            rig.arrive("a", 30)
            rig.arrive("b", 31)
            rig.newMail.check()
            rig.arrive("c", 40)
            rig.newMail.check()

            assertEquals(listOf("new_mail_count[2]", "new_mail_count[3]"), rig.notifier.shown.map { it.second })
        }

    @Test
    fun `a check with nothing new posts nothing more`() =
        runTest {
            val rig = mailRig().started()
            rig.arrive("a", 30)
            rig.newMail.check()

            rig.newMail.check()

            assertEquals(1, rig.notifier.shown.size)
        }

    @Test
    fun `mail from the account's own address is never new`() =
        runTest {
            val rig = mailRig().started("old" to 10)

            rig.arrive("mine", 30, from = OWN)
            rig.newMail.check()

            assertEquals(emptyList(), rig.notifier.shown)
        }

    @Test
    fun `older mail moved back into the inbox is not new`() =
        runTest {
            val rig = mailRig().started("recent" to 50)
            rig.arrive("archived", 20, mailbox = "archive")

            rig.moveToInbox("archived")
            rig.newMail.check()

            assertEquals(emptyList(), rig.notifier.shown)
        }

    @Test
    fun `mail that arrives already read is not new`() =
        runTest {
            val rig = mailRig().started("old" to 10)

            rig.arrive("read", 30, keywords = setOf("\$seen"))
            rig.newMail.check()

            assertEquals(emptyList(), rig.notifier.shown)
        }

    @Test
    fun `mail that arrives at the same second as the newest seen is still new`() =
        runTest {
            val rig = mailRig().started("old" to 30)

            rig.arrive("same", 30)
            rig.newMail.check()

            assertEquals(listOf("new_mail_count[1]"), rig.notifier.shown.map { it.second })
        }

    @Test
    fun `nothing is posted while the inbox is on screen, and the count starts again after`() =
        runTest {
            val rig = mailRig().started("old" to 10)
            rig.arrive("a", 30)
            rig.newMail.check()

            rig.newMail.attention(inboxInView = true, enabled = true)
            rig.arrive("b", 40)
            rig.newMail.check()
            rig.newMail.attention(inboxInView = false, enabled = true)
            rig.arrive("c", 50)
            rig.newMail.check()

            assertEquals(listOf("new_mail_count[1]", "new_mail_count[1]"), rig.notifier.shown.map { it.second })
        }

    @Test
    fun `opening the inbox clears the notification`() =
        runTest {
            val rig = mailRig().started()
            val before = rig.notifier.clears

            rig.newMail.attention(inboxInView = true, enabled = true)

            assertEquals(before + 1, rig.notifier.clears)
        }

    @Test
    fun `nothing is posted while new mail notifications are off`() =
        runTest {
            val rig = mailRig().started("old" to 10)

            rig.newMail.attention(inboxInView = false, enabled = false)
            rig.arrive("new", 30)
            rig.newMail.check()

            assertEquals(emptyList(), rig.notifier.shown)
        }

    @Test
    fun `an unreachable server posts nothing and the next check still finds the arrival`() =
        runTest {
            val rig = mailRig().started("old" to 10)
            rig.arrive("new", 30)

            rig.fake.unreachable = true
            rig.newMail.check()
            rig.fake.unreachable = false
            rig.newMail.check()

            assertEquals(listOf("new_mail_count[1]"), rig.notifier.shown.map { it.second })
        }
}
