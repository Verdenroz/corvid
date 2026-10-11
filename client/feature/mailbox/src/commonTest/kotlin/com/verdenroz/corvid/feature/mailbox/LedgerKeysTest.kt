package com.verdenroz.corvid.feature.mailbox

import androidx.compose.ui.input.key.Key
import com.verdenroz.corvid.core.model.Conversation
import com.verdenroz.corvid.core.model.EmailSummary
import com.verdenroz.corvid.core.model.Provenance
import com.verdenroz.corvid.core.model.Seal
import com.verdenroz.corvid.core.model.SenderKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

private fun row(
    id: String,
    unread: Boolean = false,
    starred: Boolean = false,
): Conversation {
    val summary =
        EmailSummary(
            id,
            id,
            "subject",
            null,
            Instant.fromEpochSeconds(0),
            if (starred) setOf("\$flagged") else emptySet(),
            "",
            Provenance(Seal.Unsigned, null, SenderKind.Person, emptyList()),
        )
    return Conversation(summary, listOf(id), if (unread) setOf(id) else emptySet())
}

class LedgerKeysTest {
    private val rows = listOf(row("a"), row("b", unread = true), row("c", starred = true))

    @Test
    fun `a shortcut acts on the ticked rows rather than the focused one`() {
        assertEquals(listOf("a", "c"), keyTarget(rows, selected = setOf("a", "c"), focused = "b").map { it.latest.id })
    }

    @Test
    fun `with nothing ticked a shortcut acts on the focused row alone`() {
        assertEquals(listOf("b"), keyTarget(rows, selected = emptySet(), focused = "b").map { it.latest.id })
    }

    @Test
    fun `with nothing ticked and nothing focused a shortcut has nothing to act on`() {
        assertEquals(emptyList(), keyTarget(rows, selected = emptySet(), focused = null))
    }

    @Test
    fun `e archives only in the inbox`() {
        val target = listOf(row("a")).target()
        assertEquals(ConversationAction.Archive, MailShortcut.Archive.action(actionsFor(ListKind.Inbox, target)))
        assertNull(MailShortcut.Archive.action(actionsFor(ListKind.Other, target)))
        assertNull(MailShortcut.Archive.action(actionsFor(ListKind.Trash, target)))
    }

    @Test
    fun `hash moves to trash, and in trash deletes for good`() {
        val target = listOf(row("a")).target()
        assertEquals(ConversationAction.Trash, MailShortcut.Delete.action(actionsFor(ListKind.Inbox, target)))
        assertEquals(ConversationAction.Trash, MailShortcut.Delete.action(actionsFor(ListKind.Other, target)))
        assertEquals(ConversationAction.Destroy, MailShortcut.Delete.action(actionsFor(ListKind.Trash, target)))
    }

    @Test
    fun `s stars an unstarred row and unstars a starred one, and does nothing in trash`() {
        assertEquals(ConversationAction.Star, MailShortcut.Star.action(actionsFor(ListKind.Inbox, listOf(row("a")).target())))
        assertEquals(
            ConversationAction.Unstar,
            MailShortcut.Star.action(actionsFor(ListKind.Inbox, listOf(row("c", starred = true)).target())),
        )
        assertNull(MailShortcut.Star.action(actionsFor(ListKind.Trash, listOf(row("a")).target())))
    }

    @Test
    fun `mark read and mark unread only fire where the button would show`() {
        val unread = listOf(row("b", unread = true)).target()
        val read = listOf(row("a")).target()
        assertEquals(ConversationAction.MarkRead, MailShortcut.MarkRead.action(actionsFor(ListKind.Inbox, unread)))
        assertNull(MailShortcut.MarkRead.action(actionsFor(ListKind.Inbox, read)))
        assertEquals(ConversationAction.MarkUnread, MailShortcut.MarkUnread.action(actionsFor(ListKind.Inbox, read)))
        assertNull(MailShortcut.MarkUnread.action(actionsFor(ListKind.Inbox, unread)))
    }

    @Test
    fun `a list's own keys never shadow a shared shortcut`() {
        listOf(Key.J, Key.K, Key.O, Key.X, Key.Enter).forEach { assertNull(Press(it).mailShortcut()) }
        listOf(Key.E, Key.S).forEach { assertNull(Press(it).listKey()) }
    }

    @Test
    fun `the inbox swipes right to archive and left to delete`() {
        assertEquals(SwipeActions(right = ConversationAction.Archive, left = ConversationAction.Trash), swipeActions(ListKind.Inbox))
    }

    @Test
    fun `trash only swipes left, and that deletes for good`() {
        assertEquals(SwipeActions(right = null, left = ConversationAction.Destroy), swipeActions(ListKind.Trash))
    }

    @Test
    fun `other lists only swipe left to delete`() {
        assertEquals(SwipeActions(right = null, left = ConversationAction.Trash), swipeActions(ListKind.Other))
    }
}
