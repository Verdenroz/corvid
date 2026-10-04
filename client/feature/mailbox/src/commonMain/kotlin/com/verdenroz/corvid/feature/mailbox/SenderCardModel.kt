package com.verdenroz.corvid.feature.mailbox

import com.verdenroz.corvid.core.data.EmailPage
import com.verdenroz.corvid.core.data.MailFilter
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.data.SenderRepository
import com.verdenroz.corvid.core.data.userMessage
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.model.EmailSummary
import com.verdenroz.corvid.core.model.SenderCounts
import com.verdenroz.corvid.core.model.SenderWindow
import com.verdenroz.corvid.core.text.UiText
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Each slot fills on its own; null means not known yet, or the count gave up, and shows no number. */
data class SenderCardState(
    val recent: List<EmailSummary>? = null,
    val counts: SenderCounts? = null,
    val window: SenderWindow? = null,
    val failed: UiText? = null,
)

/**
 * Loads one sender's card. [load] runs in the card's own composition, so closing the card or
 * locking the app cancels every scan still paging.
 */
class SenderCardModel(
    private val mailbox: MailboxRepository,
    private val senders: SenderRepository,
    val address: String,
) {
    private val _state = MutableStateFlow(SenderCardState())
    val state: StateFlow<SenderCardState> = _state

    suspend fun load() =
        coroutineScope {
            launch { fill({ recent() }) { copy(recent = it) } }
            launch { fill({ senders.counts(address) }) { copy(counts = it) } }
            launch { fill({ senders.window(address) }) { copy(window = it) } }
        }

    private suspend fun recent(): List<EmailSummary> =
        when (val page = mailbox.page(MailFilter.From(address), anchor = null)) {
            is EmailPage.Found -> page.emails.filter { it.from?.email.equals(address, ignoreCase = true) }
            is EmailPage.AnchorNotFound -> emptyList()
        }

    private suspend fun <T> fill(
        fetch: suspend () -> T,
        set: SenderCardState.(T) -> SenderCardState,
    ) {
        try {
            val value = fetch()
            _state.update { it.set(value) }
        } catch (e: JmapException) {
            _state.update { it.copy(failed = e.userMessage()) }
        }
    }
}
