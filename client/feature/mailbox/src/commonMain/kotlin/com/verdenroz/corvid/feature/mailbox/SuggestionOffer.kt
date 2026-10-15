package com.verdenroz.corvid.feature.mailbox

import com.verdenroz.corvid.core.data.FiltersFormatException
import com.verdenroz.corvid.core.data.PendingSuggestions
import com.verdenroz.corvid.core.data.SaveResult
import com.verdenroz.corvid.core.data.SuggestedRules
import com.verdenroz.corvid.core.data.userMessage
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.model.MailboxRef
import com.verdenroz.corvid.core.model.RuleSuggestion
import com.verdenroz.corvid.core.model.SuggestionAction
import com.verdenroz.corvid.core.model.SuggestionSenderKind
import com.verdenroz.corvid.core.model.folderPath
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.uiText
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
import com.verdenroz.corvid.feature.mailbox.resources.offer_not_saved
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** A rule that needs the user's say-so before it replaces the Sieve script another client has active. */
internal data class Confirmation(val suggestion: RuleSuggestion, val scriptName: String)

internal data class SuggestionOfferState(
    val pending: PendingSuggestions = PendingSuggestions(emptyList(), emptyList()),
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

    suspend fun load() {
        val pending =
            try {
                suggested.pendingNow()
            } catch (e: JmapException) {
                return
            } catch (e: FiltersFormatException) {
                return
            }
        _state.update { it.copy(pending = pending) }
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

    fun declineReplace() = _state.update { it.copy(confirming = null, failed = uiText(Res.string.offer_not_saved)) }

    private fun settle(suggestion: RuleSuggestion) =
        _state.update { it.copy(pending = PendingSuggestions(it.pending.suggestions - suggestion, it.pending.folders), working = false) }
}

/** The suggestion that is about this sender: the same address, or the same list when the card knows the list's id. */
internal fun List<RuleSuggestion>.about(
    address: String,
    listId: String?,
): RuleSuggestion? =
    firstOrNull { suggestion ->
        val value = suggestion.sender.value
        when (suggestion.sender.kind) {
            SuggestionSenderKind.From -> value.equals(address, ignoreCase = true)
            SuggestionSenderKind.List -> listId != null && value.equals(bareListId(listId), ignoreCase = true)
        }
    }

private fun bareListId(header: String) = header.substringAfter('<', header).substringBefore('>').trim()

internal fun RuleSuggestion.lead(folders: List<MailboxRef>): UiText =
    when (val filing = action) {
        SuggestionAction.Archive -> uiText(Res.string.offer_lead_archive)
        SuggestionAction.Trash -> uiText(Res.string.offer_lead_trash)
        is SuggestionAction.Folder -> uiText(Res.string.offer_lead_folder, UiText.FromServer(folders.folderPath(filing.mailboxId)))
    }

internal fun RuleSuggestion.detail(): UiText {
    val counts = uiText(Res.string.offer_counts, hits, settled)
    return spanClause(spanDays)?.let { uiText(Res.string.offer_detail_span, counts, it) } ?: uiText(Res.string.offer_detail, counts)
}

internal fun RuleSuggestion.accepts(folders: List<MailboxRef>): UiText =
    when (val filing = action) {
        SuggestionAction.Archive -> uiText(Res.string.offer_do_archive)
        SuggestionAction.Trash -> uiText(Res.string.offer_do_trash)
        is SuggestionAction.Folder -> uiText(Res.string.offer_do_folder, UiText.FromServer(folders.folderPath(filing.mailboxId)))
    }
