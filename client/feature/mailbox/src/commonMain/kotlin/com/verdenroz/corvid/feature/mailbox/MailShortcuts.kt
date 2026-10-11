package com.verdenroz.corvid.feature.mailbox

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint

/** A key going down with no Ctrl, Alt or Meta, as the shortcut tables read it; [char] is what the layout types for it. */
internal data class Press(val key: Key, val shift: Boolean = false, val char: Int = 0)

internal fun KeyEvent.press(): Press? =
    if (type != KeyEventType.KeyDown || isCtrlPressed || isMetaPressed || isAltPressed) null else Press(key, isShiftPressed, utf16CodePoint)

/** Gmail's letters for the actions a list and a reader share. */
internal enum class MailShortcut(private val wanted: List<ConversationAction>) {
    Archive(listOf(ConversationAction.Archive)),
    Delete(listOf(ConversationAction.Trash, ConversationAction.Destroy)),
    Star(listOf(ConversationAction.Star, ConversationAction.Unstar)),
    MarkUnread(listOf(ConversationAction.MarkUnread)),
    MarkRead(listOf(ConversationAction.MarkRead)),
    ;

    /** Picked from what the screen's own buttons [offered], so a key never does what no button would. */
    fun action(offered: List<ConversationAction>): ConversationAction? = wanted.firstOrNull { it in offered }
}

/** `#` by the character typed, so it works on layouts that put it somewhere other than Shift+3. */
internal fun Press.mailShortcut(): MailShortcut? =
    when {
        char == '#'.code || (key == Key.Three && shift) -> MailShortcut.Delete
        shift && key == Key.U -> MailShortcut.MarkUnread
        shift && key == Key.I -> MailShortcut.MarkRead
        shift -> null
        key == Key.E -> MailShortcut.Archive
        key == Key.S -> MailShortcut.Star
        else -> null
    }
