package com.verdenroz.corvid.feature.mailbox

import com.verdenroz.corvid.core.data.INBOX
import com.verdenroz.corvid.core.data.TRASH
import com.verdenroz.corvid.core.model.Conversation
import com.verdenroz.corvid.core.text.UiText

/** The lists a ledger tab can show; all but Starred are mailboxes found by role. */
enum class LedgerTab(val role: String?) {
    Inbox(INBOX),
    Starred(null),
    Sent("sent"),
    Drafts("drafts"),
    Trash(TRASH),
}

/** Which moves a list offers: the Inbox archives, Trash deletes for good, every other list moves to Trash. */
enum class ListKind { Inbox, Trash, Other }

/** What a ledger lists: a home tab, or the messages matching a search. */
sealed interface LedgerSource {
    data class Tab(val tab: LedgerTab) : LedgerSource

    data class Search(val query: String) : LedgerSource
}

val LedgerSource.kind: ListKind
    get() =
        when ((this as? LedgerSource.Tab)?.tab) {
            LedgerTab.Inbox -> ListKind.Inbox
            LedgerTab.Trash -> ListKind.Trash
            else -> ListKind.Other
        }

sealed interface LedgerUiState {
    data object Loading : LedgerUiState

    data class Loaded(val conversations: List<Conversation>, val canLoadMore: Boolean) : LedgerUiState

    data class Failed(val message: UiText) : LedgerUiState
}
