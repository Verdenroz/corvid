package com.verdenroz.corvid.feature.mailbox

import com.verdenroz.corvid.core.model.EmailDetail
import com.verdenroz.corvid.core.model.HeaderField
import com.verdenroz.corvid.core.model.ThreadSummary
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
     * [kind] is where the thread sits, which decides whether it archives, deletes for good or comes out of Junk.
     * [summary] is the server's, once fetched, and stays null when there is none.
     */
    data class Loaded(
        val messages: List<EmailDetail>,
        val expanded: Set<String>,
        val teach: Map<String, TeachState> = emptyMap(),
        val headers: Map<String, HeadersState> = emptyMap(),
        val kind: ListKind = ListKind.Other,
        val summary: ThreadSummary? = null,
    ) : ReaderUiState {
        val newest: EmailDetail get() = messages.last()

        /** The subject the thread started with, not the newest reply's. */
        val subject: String get() = originalSubject(messages.first().subject)
    }

    /** Deleted or moved out from under the reader between the inbox list and opening it. */
    data object Gone : ReaderUiState

    data class Failed(val message: UiText) : ReaderUiState
}

private val REPLY_PREFIXES = Regex("""^(\s*(re|fwd?)\s*(\[\d+])?\s*:)+\s*""", RegexOption.IGNORE_CASE)

/** Strips any run of Re:, Fwd: and Fw: prefixes, in any case and however repeated. */
fun originalSubject(subject: String): String = subject.replace(REPLY_PREFIXES, "").trim()
