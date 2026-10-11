package com.verdenroz.corvid.feature.mailbox

import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import com.verdenroz.corvid.core.model.Conversation

/** The keys a list answers besides the [MailShortcut]s: Gmail's j, k, o and x. */
internal enum class ListKey { Next, Previous, Open, Toggle }

internal fun Press.listKey(): ListKey? =
    if (shift) {
        null
    } else {
        when (key) {
            Key.J -> ListKey.Next
            Key.K -> ListKey.Previous
            Key.Enter, Key.NumPadEnter, Key.O -> ListKey.Open
            Key.X -> ListKey.Toggle
            else -> null
        }
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

/** With no row focused yet, either key enters [list] at its first row. */
internal fun FocusManager.step(
    key: ListKey,
    list: FocusRequester,
    rowFocused: Boolean,
): Boolean {
    if (!rowFocused) list.requestFocus()
    return moveFocus(
        when {
            !rowFocused -> FocusDirection.Enter
            key == ListKey.Next -> FocusDirection.Down
            else -> FocusDirection.Up
        },
    )
}

/** Before the focused row leaves, focus moves to a neighbour, or to the list when [toNeighbour] is false or none is left. */
internal fun FocusManager.leaveRow(
    list: FocusRequester,
    toNeighbour: Boolean,
) {
    if (!(toNeighbour && (moveFocus(FocusDirection.Down) || moveFocus(FocusDirection.Up)))) list.requestFocus()
}
