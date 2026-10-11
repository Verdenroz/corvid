package com.verdenroz.corvid.feature.mailbox

import com.verdenroz.corvid.core.model.Conversation
import com.verdenroz.corvid.core.model.EmailSummary
import com.verdenroz.corvid.core.model.Provenance
import com.verdenroz.corvid.core.model.Seal
import com.verdenroz.corvid.core.model.SenderKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

private fun conversation(
    id: String,
    unread: Boolean = false,
    starred: Boolean = false,
): Conversation {
    val keywords = if (starred) setOf("\$flagged") else emptySet()
    val summary =
        EmailSummary(
            id,
            id,
            "subject",
            null,
            Instant.fromEpochSeconds(0),
            keywords,
            "",
            Provenance(Seal.Unsigned, null, SenderKind.Person, emptyList()),
        )
    return Conversation(summary, listOf(id), if (unread) setOf(id) else emptySet())
}

class ConversationActionsTest {
    @Test
    fun `the inbox offers archive before delete and report spam`() {
        assertEquals(
            listOf(
                ConversationAction.Archive,
                ConversationAction.Trash,
                ConversationAction.ReportSpam,
                ConversationAction.MarkUnread,
                ConversationAction.Star,
            ),
            actionsFor(ListKind.Inbox, listOf(conversation("a")).target()),
        )
    }

    @Test
    fun `junk offers not spam and deleting for good, never report spam or star`() {
        assertEquals(
            listOf(ConversationAction.NotSpam, ConversationAction.Destroy, ConversationAction.MarkUnread),
            actionsFor(ListKind.Junk, listOf(conversation("a")).target()),
        )
    }

    @Test
    fun `any other list offers delete and report spam`() {
        val actions = actionsFor(ListKind.Other, listOf(conversation("a")).target())

        assertEquals(listOf(ConversationAction.Trash, ConversationAction.ReportSpam), actions.filter { it.moves })
    }

    @Test
    fun `trash offers only deleting for good and the read toggle`() {
        assertEquals(
            listOf(ConversationAction.Destroy, ConversationAction.MarkRead),
            actionsFor(ListKind.Trash, listOf(conversation("a", unread = true)).target()),
        )
    }

    @Test
    fun `a selection is unstarred only when every conversation in it is starred`() {
        val mixed = actionsFor(ListKind.Other, listOf(conversation("a", starred = true), conversation("b")).target())
        val all = actionsFor(ListKind.Other, listOf(conversation("a", starred = true), conversation("b", starred = true)).target())

        assertEquals(ConversationAction.Star, mixed.last())
        assertEquals(ConversationAction.Unstar, all.last())
    }

    @Test
    fun `a selection with any unread conversation offers mark read`() {
        val actions = actionsFor(ListKind.Other, listOf(conversation("a"), conversation("b", unread = true)).target())

        assertEquals(ConversationAction.MarkRead, actions[2])
    }
}
