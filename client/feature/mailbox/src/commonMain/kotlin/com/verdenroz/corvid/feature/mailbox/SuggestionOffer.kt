package com.verdenroz.corvid.feature.mailbox

import com.verdenroz.corvid.core.data.FiltersFormatException
import com.verdenroz.corvid.core.data.SaveResult
import com.verdenroz.corvid.core.data.SuggestedRules
import com.verdenroz.corvid.core.data.userMessage
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.model.MailboxRef
import com.verdenroz.corvid.core.model.RuleSuggestion
import com.verdenroz.corvid.core.model.SuggestionAction
import com.verdenroz.corvid.core.model.SuggestionSenderKind
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.core.ui.replaceDeclined
import com.verdenroz.corvid.core.ui.suggestion.folderText
import com.verdenroz.corvid.core.ui.suggestion.spanClause
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.offer_counts
import com.verdenroz.corvid.feature.mailbox.resources.offer_detail
import com.verdenroz.corvid.feature.mailbox.resources.offer_detail_span
import com.verdenroz.corvid.feature.mailbox.resources.offer_do_archive
import com.verdenroz.corvid.feature.mailbox.resources.offer_do_folder
import com.verdenroz.corvid.feature.mailbox.resources.offer_do_trash
import com.verdenroz.corvid.feature.mailbox.resources.offer_lead_archive
import com.verdenroz.corvid.feature.mailbox.resources.offer_lead_folder
import com.verdenroz.corvid.feature.mailbox.resources.offer_lead_trash
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** A rule that needs the user's say-so before it replaces the Sieve script another client has active. */
internal data class Confirmation(val suggestion: RuleSuggestion, val scriptName: String)

/** [checked] is the suggestion whose rules were looked up, and only then are [folders] known. */
internal data class SuggestionOfferState(
    val suggestions: List<RuleSuggestion> = emptyList(),
    val folders: List<MailboxRef> = emptyList(),
    val checked: String? = null,
    val working: Boolean = false,
    val confirming: Confirmation? = null,
    val failed: UiText? = null,
)

/**
 * The server's rule suggestions as a sender's card offers them, through the same [SuggestedRules] as Filters. They are
 * advice, so a server that has none or cannot be read leaves the card without an offer rather than failing it.
 */
internal class SuggestionOfferModel(private val suggested: SuggestedRules) {
    private val _state = MutableStateFlow(SuggestionOfferState())
    val state: StateFlow<SuggestionOfferState> = _state

    suspend fun fetch() {
        val fetched =
            try {
                suggested.fetch()
            } catch (e: JmapException) {
                return
            }
        _state.update { it.copy(suggestions = fetched) }
    }

    /** Reads the saved rules, which is the one download the card makes, only once a suggestion is about the sender. */
    suspend fun check(candidate: RuleSuggestion) {
        val set =
            try {
                suggested.current()
            } catch (e: JmapException) {
                return
            } catch (e: FiltersFormatException) {
                return
            }
        if (suggested.pending(listOf(candidate), set).isEmpty()) {
            settle(candidate)
        } else {
            _state.update {
                it.copy(folders = set.folders, checked = candidate.id)
            }
        }
    }

    suspend fun accept(
        suggestion: RuleSuggestion,
        replaceForeign: Boolean = false,
    ) {
        _state.update { it.copy(working = true, confirming = null, failed = null) }
        try {
            when (val result = suggested.accept(suggestion, replaceForeign)) {
                is SaveResult.Saved -> settle(suggestion)
                is SaveResult.NeedsConfirmation ->
                    _state.update { it.copy(working = false, confirming = Confirmation(suggestion, result.scriptName)) }
                is SaveResult.Rejected -> _state.update { it.copy(working = false, failed = result.reason) }
            }
        } catch (e: JmapException) {
            _state.update { it.copy(working = false, failed = e.userMessage()) }
        }
    }

    suspend fun dismiss(suggestion: RuleSuggestion) {
        settle(suggestion)
        try {
            suggested.dismiss(suggestion)
        } catch (e: JmapException) {
            _state.update { it.copy(failed = e.userMessage()) }
        }
    }

    fun declineReplace() = _state.update { it.copy(confirming = null, failed = replaceDeclined()) }

    private fun settle(suggestion: RuleSuggestion) = _state.update { it.copy(suggestions = it.suggestions - suggestion, working = false) }
}

/** The suggestion that is about this sender: the same address, or the same list when the card has a [listId] it can trust. */
internal fun List<RuleSuggestion>.about(
    address: String,
    listId: String?,
): RuleSuggestion? =
    firstOrNull { suggestion ->
        val value = suggestion.sender.value
        when (suggestion.sender.kind) {
            SuggestionSenderKind.From -> value.equals(address, ignoreCase = true)
            SuggestionSenderKind.List -> listId != null && value.equals(listId, ignoreCase = true)
        }
    }

internal fun RuleSuggestion.lead(folders: List<MailboxRef>): UiText =
    action.folderText(folders)?.let { uiText(Res.string.offer_lead_folder, it) }
        ?: uiText(if (action == SuggestionAction.Archive) Res.string.offer_lead_archive else Res.string.offer_lead_trash)

internal fun RuleSuggestion.detail(): UiText {
    val counts = uiText(Res.string.offer_counts, hits, settled)
    return spanClause(spanDays)?.let { uiText(Res.string.offer_detail_span, counts, it) } ?: uiText(Res.string.offer_detail, counts)
}

internal fun RuleSuggestion.accepts(folders: List<MailboxRef>): UiText =
    action.folderText(folders)?.let { uiText(Res.string.offer_do_folder, it) }
        ?: uiText(if (action == SuggestionAction.Archive) Res.string.offer_do_archive else Res.string.offer_do_trash)
