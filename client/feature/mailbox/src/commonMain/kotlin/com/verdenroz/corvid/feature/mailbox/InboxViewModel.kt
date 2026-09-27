package com.verdenroz.corvid.feature.mailbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdenroz.corvid.core.data.EmailPage
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.model.EmailSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val PAGE_SIZE = 50

/**
 * A page whose queryState differs from the one before it means the mailbox
 * changed mid-scroll (decision 8): the anchor may now point past a gap or a
 * repeat, so the whole list is refetched from the top rather than trusted.
 */
class InboxViewModel(private val repository: MailboxRepository) : ViewModel() {
    private val _state = MutableStateFlow<InboxUiState>(InboxUiState.Loading)
    val state: StateFlow<InboxUiState> = _state

    private var mailboxId: String? = null
    private var queryState: String? = null
    private var emails: List<EmailSummary> = emptyList()

    init {
        viewModelScope.launch { start() }
    }

    fun loadMore() {
        val anchor = emails.lastOrNull()?.id ?: return
        viewModelScope.launch { loadPage(anchor) }
    }

    private suspend fun start() {
        try {
            val inbox = repository.findMailboxByRole("inbox")
            if (inbox == null) {
                _state.value = InboxUiState.Failed("this account has no inbox")
                return
            }
            mailboxId = inbox.id
            loadPage(anchor = null)
        } catch (e: JmapException) {
            _state.value = InboxUiState.Failed("could not reach the server")
        }
    }

    private suspend fun loadPage(anchor: String?) {
        val id = mailboxId ?: return
        try {
            when (val page = repository.page(id, anchor)) {
                is EmailPage.AnchorNotFound -> restartFromTop()
                is EmailPage.Found -> {
                    val staleAnchor = anchor != null && queryState != null && page.queryState != queryState
                    if (staleAnchor) {
                        restartFromTop()
                        return
                    }
                    emails = if (anchor == null) page.emails else emails + page.emails
                    queryState = page.queryState
                    _state.value = InboxUiState.Loaded(emails, canLoadMore = page.emails.size == PAGE_SIZE)
                }
            }
        } catch (e: JmapException) {
            _state.value = InboxUiState.Failed("could not reach the server")
        }
    }

    private suspend fun restartFromTop() {
        emails = emptyList()
        queryState = null
        loadPage(anchor = null)
    }
}
