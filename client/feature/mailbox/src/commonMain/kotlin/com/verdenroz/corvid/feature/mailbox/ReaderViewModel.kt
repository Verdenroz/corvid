package com.verdenroz.corvid.feature.mailbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdenroz.corvid.core.data.Correction
import com.verdenroz.corvid.core.data.INBOX
import com.verdenroz.corvid.core.data.JUNK
import com.verdenroz.corvid.core.data.KeywordPatch
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.data.SummaryRepository
import com.verdenroz.corvid.core.data.TRASH
import com.verdenroz.corvid.core.data.ThreadLookup
import com.verdenroz.corvid.core.data.keywordPatch
import com.verdenroz.corvid.core.data.userMessage
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.model.CorvidKeywords
import com.verdenroz.corvid.core.model.EmailDetail
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.core.ui.launchToEnd
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.headers_gone
import com.verdenroz.corvid.feature.mailbox.resources.teach_refused
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Reads the thread holding [emailId]; its unread messages are marked read once it is on screen. [listKind] is the kind
 * of the list it was opened from, so the reader offers what that list's row did; with none it is read off the thread.
 */
class ReaderViewModel(
    private val repository: MailboxRepository,
    private val summaries: SummaryRepository,
    private val emailId: String,
    private val listKind: ListKind? = null,
) : ViewModel() {
    private val _state = MutableStateFlow<ReaderUiState>(ReaderUiState.Loading)
    val state: StateFlow<ReaderUiState> = _state

    init {
        open()
        viewModelScope.launch { repository.keywordPatches.collect(::apply) }
    }

    fun retry() {
        _state.value = ReaderUiState.Loading
        open()
    }

    private fun open() {
        viewModelScope.launch {
            try {
                load()
            } catch (e: JmapException) {
                _state.value = ReaderUiState.Failed(e.userMessage())
            }
        }
    }

    /** Shows or hides one message's headers; the first show fetches them. */
    fun toggleHeaders(id: String) {
        val current = _state.value as? ReaderUiState.Loaded ?: return
        if (id in current.headers) {
            _state.update { (it as? ReaderUiState.Loaded)?.copy(headers = it.headers - id) ?: it }
            return
        }
        setHeaders(id, HeadersState.Loading)
        viewModelScope.launch {
            val shown =
                try {
                    repository.headers(id)?.let { HeadersState.Shown(it) } ?: HeadersState.Failed(uiText(Res.string.headers_gone))
                } catch (e: JmapException) {
                    HeadersState.Failed(e.userMessage())
                }
            if ((_state.value as? ReaderUiState.Loaded)?.headers?.containsKey(id) == true) setHeaders(id, shown)
        }
    }

    private fun setHeaders(
        id: String,
        headers: HeadersState,
    ) {
        _state.update { (it as? ReaderUiState.Loaded)?.copy(headers = it.headers + (id to headers)) ?: it }
    }

    /** A message being taught is skipped: [answer] rereads its keywords from the server once the patch lands. */
    private fun apply(patch: KeywordPatch) {
        _state.update { current ->
            if (current !is ReaderUiState.Loaded || current.teach[patch.emailId]?.answering == true) return@update current
            current.copy(
                messages = current.messages.map { if (it.id == patch.emailId) it.copy(keywords = patch.applyTo(it.keywords)) else it },
            )
        }
    }

    fun toggle(id: String) {
        _state.update { current ->
            if (current !is ReaderUiState.Loaded) return@update current
            current.copy(expanded = if (id in current.expanded) current.expanded - id else current.expanded + id)
        }
    }

    /** Writes the answer, then rereads the keywords, so the labels shown are the server's, not a guess at them. */
    fun answer(
        id: String,
        correction: Correction,
    ) {
        val current = _state.value as? ReaderUiState.Loaded ?: return
        if (current.teach[id]?.answering == true) return
        setTeach(id, TeachState(answering = true))
        launchToEnd {
            try {
                val refused = repository.updateKeywords(id, correction.keywordPatch())
                if (refused == null) {
                    showKeywords(
                        id,
                    )
                } else {
                    setTeach(id, TeachState(error = uiText(Res.string.teach_refused, UiText.FromServer(refused))))
                }
            } catch (e: JmapException) {
                setTeach(id, TeachState(error = e.userMessage()))
            }
        }
    }

    private suspend fun load() {
        when (val lookup = repository.thread(emailId)) {
            is ThreadLookup.NotFound -> _state.value = ReaderUiState.Gone
            is ThreadLookup.Found -> {
                val unread = lookup.messages.filter { CorvidKeywords.SEEN !in it.keywords }.map { it.id }
                val expanded = unread.toSet() + emailId + lookup.messages.last().id
                _state.value = ReaderUiState.Loaded(lookup.messages, expanded, kind = listKind ?: kindOf(lookup.messages))
                if (unread.isNotEmpty()) launchToEnd { markRead(unread) }
                viewModelScope.launch { showSummary(lookup.threadId) }
            }
        }
    }

    private suspend fun showSummary(threadId: String) {
        val summary =
            try {
                summaries.get(threadId)
            } catch (_: JmapException) {
                null
            }
        _state.update { (it as? ReaderUiState.Loaded)?.copy(summary = summary) ?: it }
    }

    private suspend fun kindOf(messages: List<EmailDetail>): ListKind {
        val inbox = repository.mailboxId(INBOX)
        val trash = repository.mailboxId(TRASH)
        val junk = repository.mailboxId(JUNK)
        return when {
            messages.any { inbox in it.mailboxIds } -> ListKind.Inbox
            trash != null && messages.all { it.mailboxIds == setOf(trash) } -> ListKind.Trash
            junk != null && messages.all { it.mailboxIds == setOf(junk) } -> ListKind.Junk
            else -> ListKind.Other
        }
    }

    private suspend fun markRead(ids: List<String>) {
        try {
            repository.updateKeywords(ids, mapOf(CorvidKeywords.SEEN to true))
        } catch (_: JmapException) {
            // Unread stays unread and the next open tries again.
        }
    }

    private suspend fun showKeywords(id: String) {
        val keywords = repository.keywords(id)
        _state.update { current ->
            when {
                current !is ReaderUiState.Loaded -> current
                keywords == null -> current.without(id)
                else ->
                    current.copy(
                        messages = current.messages.map { if (it.id == id) it.copy(keywords = keywords) else it },
                        teach = current.teach - id,
                    )
            }
        }
    }

    private fun setTeach(
        id: String,
        teach: TeachState,
    ) {
        _state.update { current -> if (current is ReaderUiState.Loaded) current.copy(teach = current.teach + (id to teach)) else current }
    }

    private fun ReaderUiState.Loaded.without(id: String): ReaderUiState =
        messages.filter { it.id != id }.takeIf { it.isNotEmpty() }?.let { copy(messages = it, teach = teach - id) } ?: ReaderUiState.Gone
}
