package com.verdenroz.corvid.feature.mailbox

import androidx.compose.ui.input.key.Key

/**
 * Where the open conversation sits in the list it was opened from, by each neighbour's latest message id.
 * [position] counts from 1; [total] is null while the list has pages it has not loaded.
 */
data class Neighbours(val newer: String?, val older: String?, val position: Int, val total: Int?)

/** Null when [emailId] is in no loaded row, as when the list has moved on since the reader opened. */
fun LedgerUiState.Loaded.neighboursOf(emailId: String): Neighbours? {
    val index = conversations.indexOfFirst { it.latest.id == emailId || emailId in it.emailIds }
    if (index < 0) return null
    return Neighbours(
        newer = conversations.getOrNull(index - 1)?.latest?.id,
        older = conversations.getOrNull(index + 1)?.latest?.id,
        position = index + 1,
        total = conversations.size.takeUnless { canLoadMore },
    )
}

/** Where the reader goes after an action: nowhere, back to the list, or on to another conversation. */
sealed interface ReaderExit {
    data object Stay : ReaderExit

    data object Back : ReaderExit

    data class Open(val emailId: String) : ReaderExit
}

/** Archive and delete move on to the older conversation when there is one; anything else that empties the row goes back. */
fun exitAfter(
    action: ConversationAction,
    neighbours: Neighbours?,
): ReaderExit {
    if (!action.leavesList) return ReaderExit.Stay
    val advances = action == ConversationAction.Archive || action == ConversationAction.Trash || action == ConversationAction.Destroy
    val older = neighbours?.older
    return if (advances && older != null) ReaderExit.Open(older) else ReaderExit.Back
}

/** The reader's keyboard commands, Gmail's letters. */
enum class ReaderKey { Archive, Delete, Reply, ReplyAll, Forward, Star, Unread, Older, Newer, Back }

/** Null for a key the reader leaves alone, including anything held with Ctrl, Alt or Meta. */
fun readerKey(
    key: Key,
    shift: Boolean,
    modified: Boolean = false,
): ReaderKey? =
    when {
        modified -> null
        shift ->
            when (key) {
                Key.U -> ReaderKey.Unread
                Key.Three, Key.Pound -> ReaderKey.Delete
                else -> null
            }
        else ->
            when (key) {
                Key.E -> ReaderKey.Archive
                Key.R -> ReaderKey.Reply
                Key.A -> ReaderKey.ReplyAll
                Key.F -> ReaderKey.Forward
                Key.S -> ReaderKey.Star
                Key.J -> ReaderKey.Older
                Key.K -> ReaderKey.Newer
                Key.U -> ReaderKey.Back
                Key.Pound -> ReaderKey.Delete
                else -> null
            }
    }

/** The bar action a key stands for, picked from what the bar [offered] so a key never does what the bar would not. */
fun ReaderKey.action(offered: List<ConversationAction>): ConversationAction? {
    val wanted =
        when (this) {
            ReaderKey.Archive -> listOf(ConversationAction.Archive)
            ReaderKey.Delete -> listOf(ConversationAction.Trash, ConversationAction.Destroy)
            ReaderKey.Star -> listOf(ConversationAction.Star, ConversationAction.Unstar)
            ReaderKey.Unread -> listOf(ConversationAction.MarkUnread)
            else -> emptyList()
        }
    return wanted.firstOrNull { it in offered }
}
