package com.verdenroz.corvid.feature.mailbox

import com.verdenroz.corvid.core.model.EmailDetail
import com.verdenroz.corvid.core.text.UiText

sealed interface ReaderUiState {
    data object Loading : ReaderUiState

    /** [answering] while a correction is in flight; [answerError] is why the last one was not taken. */
    data class Loaded(
        val email: EmailDetail,
        val answering: Boolean = false,
        val answerError: UiText? = null,
    ) : ReaderUiState

    /** Deleted or moved out from under the reader between the inbox list and opening it. */
    data object Gone : ReaderUiState

    data class Failed(val message: UiText) : ReaderUiState
}
