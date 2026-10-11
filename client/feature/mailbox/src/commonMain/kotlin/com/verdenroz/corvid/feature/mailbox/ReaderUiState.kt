package com.verdenroz.corvid.feature.mailbox

import com.verdenroz.corvid.core.model.EmailDetail
import com.verdenroz.corvid.core.model.HeaderField
import com.verdenroz.corvid.core.text.UiText

/** [answering] while a correction on one message is in flight; [error] is why its last one was not taken. */
data class TeachState(val answering: Boolean = false, val error: UiText? = null)

/** One message's raw header block, fetched only when asked for. */
sealed interface HeadersState {
    data object Loading : HeadersState

    data class Shown(val fields: List<HeaderField>) : HeadersState

    data class Failed(val message: UiText) : HeadersState
}

sealed interface ReaderUiState {
    data object Loading : ReaderUiState

    /**
     * [messages] is the whole thread, oldest first; [expanded] are the ids shown in full, the rest as a summary line.
     * [kind] is where the thread sits, which decides whether it archives or deletes for good.
     */
    data class Loaded(
        val messages: List<EmailDetail>,
        val expanded: Set<String>,
        val teach: Map<String, TeachState> = emptyMap(),
        val headers: Map<String, HeadersState> = emptyMap(),
        val kind: ListKind = ListKind.Other,
    ) : ReaderUiState {
        val newest: EmailDetail get() = messages.last()
    }

    /** Deleted or moved out from under the reader between the inbox list and opening it. */
    data object Gone : ReaderUiState

    data class Failed(val message: UiText) : ReaderUiState
}
