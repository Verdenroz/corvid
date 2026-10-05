package com.verdenroz.corvid.feature.mailbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdenroz.corvid.core.data.EmailPage
import com.verdenroz.corvid.core.data.KeywordPatch
import com.verdenroz.corvid.core.data.MailFilter
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.data.userMessage
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.model.Conversation
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
    private var conversations: List<Conversation> = emptyList()
    private var paging: Job? = null

    init {
        viewModelScope.launch { start() }
        viewModelScope.launch { repository.keywordPatches.collect(::apply) }
    }

    fun loadMore() {
        if (paging?.isActive == true) return
        val anchor = conversations.lastOrNull()?.latest?.id ?: return
        paging = viewModelScope.launch { loadPage(anchor) }
    }

    fun refresh() {
        if (_state.value is LedgerUiState.Loaded) viewModelScope.launch { restartFromTop() }
    }

    private fun apply(patch: KeywordPatch) {
        val loaded = _state.value as? LedgerUiState.Loaded ?: return
        if (conversations.none { patch.emailId in it.emailIds }) return
        conversations = conversations.map(patch::applyTo)
        _state.value = loaded.copy(conversations = conversations)
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
        return id?.let { MailFilter.InMailbox(it, threaded = tab != LedgerTab.Drafts) }
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
                    conversations = if (anchor == null) page.conversations else conversations + page.conversations
                    queryState = page.queryState
                    _state.value = LedgerUiState.Loaded(conversations, canLoadMore = page.conversations.size == PAGE_SIZE)
                }
            }
        } catch (e: JmapException) {
            _state.value = LedgerUiState.Failed(e.userMessage())
        }
    }

    private suspend fun restartFromTop() {
        conversations = emptyList()
        queryState = null
        loadPage(anchor = null)
    }
}
