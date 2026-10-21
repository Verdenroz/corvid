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
import com.verdenroz.corvid.core.text.UiText
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

    private val _total = MutableStateFlow<Int?>(null)

    /** How many messages a search matched in all, as the server counted them with its first page; null for a tab. */
    val total: StateFlow<Int?> = _total

    private var filter: MailFilter? = null
    private var queryState: String? = null
    private var conversations: List<Conversation> = emptyList()
    private var canLoadMore = false
    private var moreFailed: UiText? = null
    private var refreshFailed: UiText? = null
    private var hidden: Set<String> = emptySet()
    private var settled: Set<String> = emptySet()
    private var paging: Job? = null
    private var reload: Job? = null
    private var onScreen = false
    private var stale = false

    init {
        viewModelScope.launch { start() }
        viewModelScope.launch { repository.keywordPatches.collect(::apply) }
        viewModelScope.launch { repository.moves.collect(::onMove) }
    }

    fun loadMore() {
        if (paging?.isActive == true) return
        val anchor = conversations.lastOrNull()?.latest?.id ?: return
        if (moreFailed != null) {
            moreFailed = null
            publish()
        }
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

    /** A list whose mailbox did not exist yet looks for it again, as archiving makes one on first use; a failed one tries again. */
    fun refresh(): Job? {
        if (_state.value is LedgerUiState.Loading) return null
        if (refreshFailed != null && _state.value is LedgerUiState.Loaded) {
            refreshFailed = null
            publish()
        }
        return reload()
    }

    fun retry() {
        _state.value = LedgerUiState.Loading
        reload()
    }

    /** The mailbox changed on the server: a list on screen refetches now, one off screen when it is next shown. */
    fun mailChanged() {
        if (onScreen) refresh() else stale = true
    }

    fun shown(onScreen: Boolean) {
        this.onScreen = onScreen
        if (onScreen && stale) {
            stale = false
            refresh()
        }
    }

    /** One refetch from the top at a time: a newer one cancels the one in flight, whose answer it would supersede. */
    private fun reload(): Job {
        reload?.cancel()
        return viewModelScope.launch { if (filter == null) start() else loadPage(anchor = null) }.also { reload = it }
    }

    private fun apply(patch: KeywordPatch) {
        if (_state.value !is LedgerUiState.Loaded || conversations.none { patch.emailId in it.emailIds }) return
        conversations = conversations.map(patch::applyTo)
        publish()
    }

    /** A row whose messages are moving is hidden at once, and shown again by the first refetch after the move ends. */
    private fun onMove(move: MoveEvent) {
        if (_state.value !is LedgerUiState.Loaded || conversations.none { it.emailIds.any(move.ids::contains) }) return
        if (move.done) {
            settled = settled + move.ids
            reload()
        } else {
            hidden = hidden + move.ids
            publish()
        }
    }

    private fun publish() {
        _state.value =
            LedgerUiState.Loaded(conversations.filter { it.emailIds.none(hidden::contains) }, canLoadMore, moreFailed, refreshFailed)
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
        if (id == null) {
            _state.value =
                if (tab == LedgerTab.Archive) {
                    LedgerUiState.Loaded(emptyList(), canLoadMore = false)
                } else {
                    LedgerUiState.Failed(uiText(Res.string.mailbox_no_mailbox, role))
                }
        }
        return id?.let { MailFilter.InMailbox(it, threaded = tab != LedgerTab.Drafts) }
    }

    private suspend fun loadPage(anchor: String?) {
        val current = filter ?: return
        try {
            when (val page = repository.page(current, anchor)) {
                is EmailPage.AnchorNotFound -> loadPage(anchor = null)
                is EmailPage.Found -> {
                    val staleAnchor = anchor != null && queryState != null && page.queryState != queryState
                    if (staleAnchor) {
                        loadPage(anchor = null)
                        return
                    }
                    conversations = if (anchor == null) page.conversations else conversations + page.conversations
                    moreFailed = null
                    if (anchor == null) {
                        refreshFailed = null
                        hidden = hidden - settled
                        settled = emptySet()
                    }
                    queryState = page.queryState
                    canLoadMore = page.conversations.size == PAGE_SIZE
                    if (anchor == null) _total.value = page.total
                    publish()
                    if (anchor == null) _selected.update { selected -> selected intersect conversations.map { it.latest.id }.toSet() }
                }
            }
        } catch (e: JmapException) {
            if (_state.value is LedgerUiState.Loaded && conversations.isNotEmpty()) {
                if (anchor == null) refreshFailed = e.userMessage() else moreFailed = e.userMessage()
                publish()
            } else {
                _state.value = LedgerUiState.Failed(e.userMessage())
            }
        }
    }
}
