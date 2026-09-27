package com.verdenroz.corvid.feature.mailbox

sealed interface ReaderUiState {
    data object Loading : ReaderUiState

    data class Loaded(
        val subject: String,
        val from: String,
        val to: List<String>,
        val cc: List<String>,
        val body: String,
        val bodyTruncated: Boolean,
    ) : ReaderUiState

    /** Deleted or moved out from under the reader between the inbox list and opening it. */
    data object Gone : ReaderUiState

    data class Failed(val message: String) : ReaderUiState
}
