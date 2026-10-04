package com.verdenroz.corvid.feature.mailbox

import com.verdenroz.corvid.core.model.EmailDetail

sealed interface ReaderUiState {
    data object Loading : ReaderUiState

    data class Loaded(val email: EmailDetail) : ReaderUiState

    /** Deleted or moved out from under the reader between the inbox list and opening it. */
    data object Gone : ReaderUiState

    data class Failed(val message: String) : ReaderUiState
}
