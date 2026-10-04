package com.verdenroz.corvid.feature.mailbox

import com.verdenroz.corvid.core.model.EmailSummary
import com.verdenroz.corvid.core.text.UiText

/** The lists a ledger tab can show; the first three are mailboxes found by role. */
enum class LedgerTab(val role: String?) {
    Inbox("inbox"),
    Starred(null),
    Sent("sent"),
    Drafts("drafts"),
}

sealed interface LedgerUiState {
    data object Loading : LedgerUiState

    data class Loaded(val emails: List<EmailSummary>, val canLoadMore: Boolean) : LedgerUiState

    data class Failed(val message: UiText) : LedgerUiState
}
