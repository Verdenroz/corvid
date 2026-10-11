package com.verdenroz.corvid.feature.mailbox

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
        assertEquals(ConversationAction.Archive, ListKey.Archive.action(ListKind.Inbox, target))
        assertNull(ListKey.Archive.action(ListKind.Other, target))
        assertNull(ListKey.Archive.action(ListKind.Trash, target))
    }

    @Test
    fun `hash moves to trash, and in trash deletes for good`() {
        val target = listOf(row("a")).target()
        assertEquals(ConversationAction.Trash, ListKey.Delete.action(ListKind.Inbox, target))
        assertEquals(ConversationAction.Trash, ListKey.Delete.action(ListKind.Other, target))
        assertEquals(ConversationAction.Destroy, ListKey.Delete.action(ListKind.Trash, target))
    }

    @Test
    fun `s stars an unstarred row and unstars a starred one, and does nothing in trash`() {
        assertEquals(ConversationAction.Star, ListKey.Star.action(ListKind.Inbox, listOf(row("a")).target()))
        assertEquals(ConversationAction.Unstar, ListKey.Star.action(ListKind.Inbox, listOf(row("c", starred = true)).target()))
        assertNull(ListKey.Star.action(ListKind.Trash, listOf(row("a")).target()))
    }

    @Test
    fun `mark read and mark unread only fire where the button would show`() {
        val unread = listOf(row("b", unread = true)).target()
        val read = listOf(row("a")).target()
        assertEquals(ConversationAction.MarkRead, ListKey.MarkRead.action(ListKind.Inbox, unread))
        assertNull(ListKey.MarkRead.action(ListKind.Inbox, read))
        assertEquals(ConversationAction.MarkUnread, ListKey.MarkUnread.action(ListKind.Inbox, read))
        assertNull(ListKey.MarkUnread.action(ListKind.Inbox, unread))
    }

    @Test
    fun `movement keys name no action`() {
        val target = listOf(row("a")).target()
        listOf(ListKey.Next, ListKey.Previous, ListKey.Open, ListKey.Toggle).forEach { assertNull(it.action(ListKind.Inbox, target)) }
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
