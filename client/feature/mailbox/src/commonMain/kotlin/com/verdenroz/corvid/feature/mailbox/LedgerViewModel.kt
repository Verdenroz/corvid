package com.verdenroz.corvid.feature.mailbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdenroz.corvid.core.data.EmailPage
import com.verdenroz.corvid.core.data.KeywordPatch
import com.verdenroz.corvid.core.data.MailFilter
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.data.userMessage
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.model.EmailSummary
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.mailbox_no_mailbox
import kotlinx.coroutines.Job
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
    private val source: LedgerSource,
) : ViewModel() {
    constructor(repository: MailboxRepository, tab: LedgerTab) : this(repository, LedgerSource.Tab(tab))

    private val _state = MutableStateFlow<LedgerUiState>(LedgerUiState.Loading)
    val state: StateFlow<LedgerUiState> = _state

    private var filter: MailFilter? = null
    private var queryState: String? = null
    private var emails: List<EmailSummary> = emptyList()
    private var paging: Job? = null

    init {
        viewModelScope.launch { start() }
        viewModelScope.launch { repository.keywordPatches.collect(::apply) }
    }

    fun loadMore() {
        if (paging?.isActive == true) return
        val anchor = emails.lastOrNull()?.id ?: return
        paging = viewModelScope.launch { loadPage(anchor) }
    }

    fun refresh() {
        if (_state.value is LedgerUiState.Loaded) viewModelScope.launch { restartFromTop() }
    }

    private fun apply(patch: KeywordPatch) {
        val loaded = _state.value as? LedgerUiState.Loaded ?: return
        if (emails.none { it.id == patch.emailId }) return
        emails = emails.map { if (it.id == patch.emailId) it.copy(keywords = patch.applyTo(it.keywords)) else it }
        _state.value = loaded.copy(emails = emails)
    }

    private suspend fun start() {
        try {
            filter = filterOf(source) ?: return
            loadPage(anchor = null)
        } catch (e: JmapException) {
            _state.value = LedgerUiState.Failed(e.userMessage())
        }
    }

    private suspend fun filterOf(source: LedgerSource): MailFilter? =
        when (source) {
            is LedgerSource.Search -> MailFilter.Search(source.query)
            is LedgerSource.Tab -> tabFilter(source.tab)
        }

    private suspend fun tabFilter(tab: LedgerTab): MailFilter? {
        val role = tab.role ?: return MailFilter.Starred
        val id = repository.mailboxId(role)
        if (id == null) _state.value = LedgerUiState.Failed(uiText(Res.string.mailbox_no_mailbox, role))
        return id?.let(MailFilter::InMailbox)
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
