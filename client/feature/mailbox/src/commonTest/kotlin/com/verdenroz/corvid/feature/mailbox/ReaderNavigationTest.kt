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

private fun thread(vararg ids: String): Conversation {
    val latest =
        EmailSummary(
            ids.last(),
            ids.first(),
            "subject",
            null,
            Instant.fromEpochSeconds(0),
            emptySet(),
            "",
            Provenance(Seal.Unsigned, null, SenderKind.Person, emptyList()),
        )
    return Conversation(latest, ids.toList(), emptySet())
}

private fun list(
    vararg threads: Conversation,
    canLoadMore: Boolean = false,
) = LedgerUiState.Loaded(threads.toList(), canLoadMore)

class ReaderNavigationTest {
    @Test
    fun `a conversation's neighbours are the rows above and below it, by their latest message`() {
        val shown = list(thread("a"), thread("b1", "b2"), thread("c"))

        assertEquals(Neighbours(newer = "a", older = "c", position = 2, total = 3), shown.neighboursOf("b2"))
    }

    @Test
    fun `a reader opened on an earlier message of a thread still finds its row`() {
        val shown = list(thread("a"), thread("b1", "b2"), thread("c"))

        assertEquals(2, shown.neighboursOf("b1")?.position)
    }

    @Test
    fun `the ends of the list have no neighbour past them`() {
        val shown = list(thread("a"), thread("b"))

        assertNull(shown.neighboursOf("a")?.newer)
        assertNull(shown.neighboursOf("b")?.older)
    }

    @Test
    fun `the total is unknown while the list has pages left to load`() {
        assertNull(list(thread("a"), thread("b"), canLoadMore = true).neighboursOf("a")?.total)
    }

    @Test
    fun `a conversation no longer in the list has no neighbours`() {
        assertNull(list(thread("a")).neighboursOf("gone"))
    }

    @Test
    fun `archive and delete move on to the older conversation`() {
        val middle = Neighbours(newer = "a", older = "c", position = 2, total = 3)

        assertEquals(ReaderExit.Open("c"), exitAfter(ConversationAction.Archive, middle))
        assertEquals(ReaderExit.Open("c"), exitAfter(ConversationAction.Trash, middle))
        assertEquals(ReaderExit.Open("c"), exitAfter(ConversationAction.Destroy, middle))
    }

    @Test
    fun `archive on the oldest conversation, or one opened from outside a list, goes back`() {
        assertEquals(ReaderExit.Back, exitAfter(ConversationAction.Archive, Neighbours("a", null, 2, 2)))
        assertEquals(ReaderExit.Back, exitAfter(ConversationAction.Trash, null))
    }

    @Test
    fun `mark unread goes back to the list and star stays put`() {
        val middle = Neighbours(newer = "a", older = "c", position = 2, total = 3)

        assertEquals(ReaderExit.Back, exitAfter(ConversationAction.MarkUnread, middle))
        assertEquals(ReaderExit.Stay, exitAfter(ConversationAction.Star, middle))
        assertEquals(ReaderExit.Stay, exitAfter(ConversationAction.Unstar, middle))
    }

    @Test
    fun `the reader answers gmail's letters`() {
        assertEquals(ReaderKey.Reply, Press(Key.R).readerKey())
        assertEquals(ReaderKey.ReplyAll, Press(Key.A).readerKey())
        assertEquals(ReaderKey.Forward, Press(Key.F).readerKey())
        assertEquals(ReaderKey.Older, Press(Key.J).readerKey())
        assertEquals(ReaderKey.Newer, Press(Key.K).readerKey())
        assertEquals(ReaderKey.Back, Press(Key.U).readerKey())
        assertEquals(MailShortcut.Archive, Press(Key.E).mailShortcut())
        assertEquals(MailShortcut.Star, Press(Key.S).mailShortcut())
        assertEquals(MailShortcut.MarkUnread, Press(Key.U, shift = true).mailShortcut())
        assertEquals(MailShortcut.Delete, Press(Key.Three, shift = true).mailShortcut())
    }

    @Test
    fun `hash deletes by the character typed, and a shifted letter is not its plain one`() {
        assertEquals(MailShortcut.Delete, Press(Key.Pound, char = '#'.code).mailShortcut())
        assertNull(Press(Key.Three).mailShortcut())
        assertNull(Press(Key.E, shift = true).mailShortcut())
        assertNull(Press(Key.U, shift = true).readerKey())
    }

    @Test
    fun `a key does only what the bar offers`() {
        val inbox = listOf(ConversationAction.Archive, ConversationAction.Trash, ConversationAction.MarkUnread, ConversationAction.Star)
        val trash = listOf(ConversationAction.Destroy, ConversationAction.MarkUnread)

        assertEquals(ConversationAction.Archive, MailShortcut.Archive.action(inbox))
        assertNull(MailShortcut.Archive.action(trash))
        assertEquals(ConversationAction.Trash, MailShortcut.Delete.action(inbox))
        assertEquals(ConversationAction.Destroy, MailShortcut.Delete.action(trash))
        assertEquals(ConversationAction.Unstar, MailShortcut.Star.action(listOf(ConversationAction.Unstar)))
        assertNull(MailShortcut.Star.action(trash))
    }

    @Test
    fun `the title strips reply and forward prefixes however they are cased or repeated`() {
        assertEquals("Lunch", originalSubject("Re: Lunch"))
        assertEquals("Lunch", originalSubject("RE: Fwd: re:Lunch"))
        assertEquals("Lunch", originalSubject("Fw: FW: Lunch"))
        assertEquals("Lunch", originalSubject("Re[2]: Lunch"))
        assertEquals("Reply hazy: try again", originalSubject("Reply hazy: try again"))
        assertEquals("", originalSubject("Re:"))
    }
}
