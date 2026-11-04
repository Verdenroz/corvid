package com.verdenroz.corvid.feature.outbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdenroz.corvid.core.data.outbox.Contents
import com.verdenroz.corvid.core.data.outbox.Outbox
import com.verdenroz.corvid.core.data.outbox.OutboxEntry
import com.verdenroz.corvid.core.data.outbox.OutboxState
import com.verdenroz.corvid.core.data.outbox.RejectionCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

private val SENT_SHOWN_FOR = 1.days

enum class OutboxStatus { Waiting, Sending, NeedsDecision, NotSent, CannotOpen, SealedToPreviousKey, Sent }

/** [recipients] and [subject] are null unless this device key opened the message. */
data class OutboxRow(
    val id: String,
    val status: OutboxStatus,
    val rejection: RejectionCategory? = null,
    val recipients: String? = null,
    val subject: String? = null,
    val sentAt: Instant? = null,
    val mayHaveBeenSent: Boolean = false,
    val canResend: Boolean = false,
    val canDiscard: Boolean = false,
)

/** [confirming] is the row whose Discard is waiting on an answer. */
data class OutboxListState(
    val rows: List<OutboxRow> = emptyList(),
    val confirming: String? = null,
) {
    val waiting: Boolean get() = rows.any { it.status == OutboxStatus.Waiting }
}

class OutboxViewModel(
    private val outbox: Outbox,
    private val clock: Clock = Clock.System,
) : ViewModel() {
    private val confirming = MutableStateFlow<String?>(null)

    val state: StateFlow<OutboxListState> =
        combine(outbox.entries, confirming) { entries, confirming -> OutboxListState(entries.mapNotNull(::row), confirming) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, OutboxListState(outbox.entries.value.mapNotNull(::row)))

    fun resend(id: String) {
        viewModelScope.launch { outbox.resend(id) }
    }

    fun tryNow() = outbox.drainSoon()

    fun askDiscard(id: String) {
        confirming.value = id
    }

    fun keep() {
        confirming.value = null
    }

    fun discard(id: String) {
        confirming.value = null
        viewModelScope.launch { outbox.discard(id) }
    }

    private fun row(entry: OutboxEntry): OutboxRow? {
        val id = entry.item.id
        val state = entry.item.state
        val maybeSent = state == OutboxState.Unconfirmed
        return when (val contents = entry.contents) {
            null -> sentRow(id, state as OutboxState.Sent)
            Contents.SealedToOtherKey -> OutboxRow(id, OutboxStatus.SealedToPreviousKey, mayHaveBeenSent = maybeSent, canDiscard = true)
            Contents.Unreadable -> OutboxRow(id, OutboxStatus.CannotOpen, mayHaveBeenSent = maybeSent, canDiscard = true)
            is Contents.Readable -> readableRow(id, state, contents)
        }
    }

    private fun sentRow(
        id: String,
        state: OutboxState.Sent,
    ): OutboxRow? =
        OutboxRow(id, OutboxStatus.Sent, sentAt = Instant.fromEpochMilliseconds(state.at))
            .takeIf { clock.now().toEpochMilliseconds() - state.at < SENT_SHOWN_FOR.inWholeMilliseconds }

    private fun readableRow(
        id: String,
        state: OutboxState,
        contents: Contents.Readable,
    ): OutboxRow {
        val waiting =
            OutboxRow(id, OutboxStatus.Waiting, recipients = contents.draft.to.joinToString { it.email }, subject = contents.draft.subject)
        return when (state) {
            OutboxState.Queued -> waiting
            is OutboxState.Submitting -> waiting.copy(status = OutboxStatus.Sending)
            OutboxState.Unconfirmed ->
                waiting.copy(status = OutboxStatus.NeedsDecision, mayHaveBeenSent = true, canResend = true, canDiscard = true)
            is OutboxState.Rejected ->
                waiting.copy(status = OutboxStatus.NotSent, rejection = state.category, canResend = true, canDiscard = true)
            is OutboxState.Sent -> error("a sent message keeps no contents")
        }
    }
}
