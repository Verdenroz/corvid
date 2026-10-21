package com.verdenroz.corvid.feature.mailbox

import com.verdenroz.corvid.core.data.ARCHIVE
import com.verdenroz.corvid.core.data.DRAFTS
import com.verdenroz.corvid.core.data.INBOX
import com.verdenroz.corvid.core.data.JUNK
import com.verdenroz.corvid.core.data.SENT
import com.verdenroz.corvid.core.data.TRASH
import com.verdenroz.corvid.core.model.Conversation
import com.verdenroz.corvid.core.text.UiText

/** The lists a ledger tab can show; all but Starred are mailboxes found by role. */
enum class LedgerTab(val role: String?) {
    Inbox(INBOX),
    Starred(null),
    Sent(SENT),
    Drafts(DRAFTS),
    Archive(ARCHIVE),
    Junk(JUNK),
    Trash(TRASH),
}

/** Which moves a list offers: the Inbox archives, Trash deletes for good, Junk deletes or rescues, every other list moves to Trash. */
enum class ListKind { Inbox, Trash, Junk, Other }

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
            LedgerTab.Junk -> ListKind.Junk
            else -> ListKind.Other
        }

sealed interface LedgerUiState {
    data object Loading : LedgerUiState

    /** [moreFailed] and [refreshFailed] say why a later page or a refetch from the top did not load; the rows shown stay. */
    data class Loaded(
        val conversations: List<Conversation>,
        val canLoadMore: Boolean,
        val moreFailed: UiText? = null,
        val refreshFailed: UiText? = null,
    ) : LedgerUiState

    data class Failed(val message: UiText) : LedgerUiState
}
