package com.verdenroz.corvid.feature.mailbox

import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
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
import com.verdenroz.corvid.core.model.Conversation

/** Gmail's list shortcuts, as far as a ledger has the action behind them. */
internal enum class ListKey { Next, Previous, Open, Toggle, Archive, Delete, Star, MarkUnread, MarkRead }

internal fun KeyEvent.listKey(): ListKey? {
    if (type != KeyEventType.KeyDown || isCtrlPressed || isMetaPressed || isAltPressed) return null
    if (utf16CodePoint == '#'.code || (key == Key.Three && isShiftPressed)) return ListKey.Delete
    if (isShiftPressed) {
        return when (key) {
            Key.U -> ListKey.MarkUnread
            Key.I -> ListKey.MarkRead
            else -> null
        }
    }
    return when (key) {
        Key.J -> ListKey.Next
        Key.K -> ListKey.Previous
        Key.Enter, Key.NumPadEnter, Key.O -> ListKey.Open
        Key.X -> ListKey.Toggle
        Key.E -> ListKey.Archive
        Key.S -> ListKey.Star
        else -> null
    }
}

/** Only what the [kind] list's own buttons offer for [target], so a key never does what no button would. */
internal fun ListKey.action(
    kind: ListKind,
    target: ActionTarget,
): ConversationAction? {
    val wanted =
        when (this) {
            ListKey.Archive -> setOf(ConversationAction.Archive)
            ListKey.Delete -> setOf(ConversationAction.Trash, ConversationAction.Destroy)
            ListKey.Star -> setOf(ConversationAction.Star, ConversationAction.Unstar)
            ListKey.MarkUnread -> setOf(ConversationAction.MarkUnread)
            ListKey.MarkRead -> setOf(ConversationAction.MarkRead)
            ListKey.Next, ListKey.Previous, ListKey.Open, ListKey.Toggle -> return null
        }
    return actionsFor(kind, target).firstOrNull { it in wanted }
}

/** A shortcut acts on the ticked rows when there are any, otherwise on the focused one. */
internal fun keyTarget(
    conversations: List<Conversation>,
    selected: Set<String>,
    focused: String?,
): List<Conversation> =
    when {
        selected.isNotEmpty() -> conversations.filter { it.latest.id in selected }
        else -> conversations.filter { it.latest.id == focused }
    }

/** j and k stop at the list's ends: a list cancels any vertical move out of it, rather than walk into the tabs above. */
internal val FocusDirection.vertical: Boolean
    get() = this == FocusDirection.Up || this == FocusDirection.Down

/** With no row focused yet, either key enters the list at its first row. */
internal fun FocusManager.step(
    key: ListKey,
    rowFocused: Boolean,
): Boolean =
    moveFocus(
        when {
            !rowFocused -> FocusDirection.Enter
            key == ListKey.Next -> FocusDirection.Down
            else -> FocusDirection.Up
        },
    )

/** Before the focused row leaves, focus moves to a neighbour, or to the list when [toNeighbour] is false or none is left. */
internal fun FocusManager.leaveRow(
    list: FocusRequester,
    toNeighbour: Boolean,
) {
    if (!(toNeighbour && (moveFocus(FocusDirection.Down) || moveFocus(FocusDirection.Up)))) list.requestFocus()
}
