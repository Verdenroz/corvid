package com.verdenroz.corvid.feature.mailbox

import com.verdenroz.corvid.core.model.EmailSummary

sealed interface InboxUiState {
    data object Loading : InboxUiState

    data class Loaded(val emails: List<EmailSummary>, val canLoadMore: Boolean) : InboxUiState

    data class Failed(val message: String) : InboxUiState
}
