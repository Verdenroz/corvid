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

/** Every move, spam and not spam included, goes on to the older conversation when there is one; marking unread goes back. */
fun exitAfter(
    act: Act,
    neighbours: Neighbours?,
): ReaderExit {
    if (!act.leavesList) return ReaderExit.Stay
    val older = neighbours?.older
    return if (act.moves && older != null) ReaderExit.Open(older) else ReaderExit.Back
}

/** The keys a reader answers besides the [MailShortcut]s, Gmail's letters. */
internal enum class ReaderKey { Reply, ReplyAll, Forward, Older, Newer, Back }

internal fun Press.readerKey(): ReaderKey? =
    if (shift) {
        null
    } else {
        when (key) {
            Key.R -> ReaderKey.Reply
            Key.A -> ReaderKey.ReplyAll
            Key.F -> ReaderKey.Forward
            Key.J -> ReaderKey.Older
            Key.K -> ReaderKey.Newer
            Key.U -> ReaderKey.Back
            else -> null
        }
    }
