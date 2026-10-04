package com.verdenroz.corvid.feature.mailbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdenroz.corvid.core.data.EmailPage
import com.verdenroz.corvid.core.data.MailFilter
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.data.userMessage
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.model.EmailSummary
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.mailbox_no_mailbox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val PAGE_SIZE = 50

/**
 * A page whose queryState differs from the one before it means the mailbox
 * changed mid-scroll: the anchor may now point past a gap or a
 * repeat, so the whole list is refetched from the top rather than trusted.
 */
class LedgerViewModel(
    private val repository: MailboxRepository,
    private val tab: LedgerTab,
) : ViewModel() {
    private val _state = MutableStateFlow<LedgerUiState>(LedgerUiState.Loading)
    val state: StateFlow<LedgerUiState> = _state

    private var filter: MailFilter? = null
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
            val role = tab.role
            filter =
                if (role == null) {
                    MailFilter.Starred
                } else {
                    val mailbox = repository.findMailboxByRole(role)
                    if (mailbox == null) {
                        _state.value = LedgerUiState.Failed(uiText(Res.string.mailbox_no_mailbox, role))
                        return
                    }
                    MailFilter.InMailbox(mailbox.id)
                }
            loadPage(anchor = null)
        } catch (e: JmapException) {
            _state.value = LedgerUiState.Failed(e.userMessage())
        }
    }

    private suspend fun loadPage(anchor: String?) {
        val current = filter ?: return
        try {
            when (val page = repository.page(current, anchor)) {
                is EmailPage.AnchorNotFound -> restartFromTop()
                is EmailPage.Found -> {
                    val staleAnchor = anchor != null && queryState != null && page.queryState != queryState
                    if (staleAnchor) {
                        restartFromTop()
                        return
                    }
                    emails = if (anchor == null) page.emails else emails + page.emails
                    queryState = page.queryState
                    _state.value = LedgerUiState.Loaded(emails, canLoadMore = page.emails.size == PAGE_SIZE)
                }
            }
        } catch (e: JmapException) {
            _state.value = LedgerUiState.Failed(e.userMessage())
        }
    }

    private suspend fun restartFromTop() {
        emails = emptyList()
        queryState = null
        loadPage(anchor = null)
    }
}
