package com.verdenroz.corvid.feature.mailbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdenroz.corvid.core.data.EmailPage
import com.verdenroz.corvid.core.data.KeywordPatch
import com.verdenroz.corvid.core.data.MailFilter
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.data.MoveEvent
import com.verdenroz.corvid.core.data.userMessage
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.model.Conversation
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.mailbox_no_mailbox
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val PAGE_SIZE = 50

/**
 * A page whose queryState differs from the one before it means the mailbox
 * changed mid-scroll: the anchor may now point past a gap or a
 * repeat, so the whole list is refetched from the top rather than trusted.
 */
class LedgerViewModel(
    private val repository: MailboxRepository,
    val source: LedgerSource,
) : ViewModel() {
    constructor(repository: MailboxRepository, tab: LedgerTab) : this(repository, LedgerSource.Tab(tab))

    private val _state = MutableStateFlow<LedgerUiState>(LedgerUiState.Loading)
    val state: StateFlow<LedgerUiState> = _state

    private val _selected = MutableStateFlow<Set<String>>(emptySet())

    /** The rows ticked for a bulk action, by the id of each conversation's latest message. */
    val selected: StateFlow<Set<String>> = _selected

    private var filter: MailFilter? = null
    private var queryState: String? = null
    private var conversations: List<Conversation> = emptyList()
    private var canLoadMore = false
    private var hidden: Set<String> = emptySet()
    private var paging: Job? = null

    init {
        viewModelScope.launch { start() }
        viewModelScope.launch { repository.keywordPatches.collect(::apply) }
        viewModelScope.launch { repository.moves.collect(::onMove) }
    }

    fun loadMore() {
        if (paging?.isActive == true) return
        val anchor = conversations.lastOrNull()?.latest?.id ?: return
        paging = viewModelScope.launch { loadPage(anchor) }
    }

    fun toggleSelected(id: String) {
        _selected.update { if (id in it) it - id else it + id }
    }

    fun selectAll() {
        _selected.value = (_state.value as? LedgerUiState.Loaded)?.conversations?.map { it.latest.id }?.toSet().orEmpty()
    }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    fun refresh(): Job? = if (_state.value is LedgerUiState.Loaded) viewModelScope.launch { restartFromTop() } else null

    private fun apply(patch: KeywordPatch) {
        if (_state.value !is LedgerUiState.Loaded || conversations.none { patch.emailId in it.emailIds }) return
        conversations = conversations.map(patch::applyTo)
        publish()
    }

    /** A row whose messages are moving is hidden at once, then refetched once the move ends, whichever way it went. */
    private fun onMove(move: MoveEvent) {
        if (_state.value !is LedgerUiState.Loaded || conversations.none { it.emailIds.any(move.ids::contains) }) return
        if (!move.done) {
            hidden = hidden + move.ids
            publish()
            return
        }
        viewModelScope.launch {
            restartFromTop()
            hidden = hidden - move.ids
            if (_state.value is LedgerUiState.Loaded) publish()
        }
    }

    private fun publish() {
        _state.value = LedgerUiState.Loaded(conversations.filter { it.emailIds.none(hidden::contains) }, canLoadMore)
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
                    canLoadMore = page.conversations.size == PAGE_SIZE
                    publish()
                    if (anchor == null) _selected.update { selected -> selected intersect conversations.map { it.latest.id }.toSet() }
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
