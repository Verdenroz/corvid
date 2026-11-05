package com.verdenroz.corvid.feature.outbox

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.data.outbox.RejectionCategory
import com.verdenroz.corvid.core.designsystem.CorvidSpacing
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.LocalWideLayout
import com.verdenroz.corvid.core.designsystem.component.QuietLink
import com.verdenroz.corvid.core.ui.DiscardQuestion
import com.verdenroz.corvid.core.ui.PageHeader
import com.verdenroz.corvid.feature.outbox.resources.Res
import com.verdenroz.corvid.feature.outbox.resources.outbox_back
import com.verdenroz.corvid.feature.outbox.resources.outbox_cannot_open_detail
import com.verdenroz.corvid.feature.outbox.resources.outbox_decision_detail
import com.verdenroz.corvid.feature.outbox.resources.outbox_detail
import com.verdenroz.corvid.feature.outbox.resources.outbox_discard
import com.verdenroz.corvid.feature.outbox.resources.outbox_discard_named
import com.verdenroz.corvid.feature.outbox.resources.outbox_discard_question
import com.verdenroz.corvid.feature.outbox.resources.outbox_discard_question_maybe_sent
import com.verdenroz.corvid.feature.outbox.resources.outbox_discard_question_unopenable
import com.verdenroz.corvid.feature.outbox.resources.outbox_empty
import com.verdenroz.corvid.feature.outbox.resources.outbox_keep
import com.verdenroz.corvid.feature.outbox.resources.outbox_message
import com.verdenroz.corvid.feature.outbox.resources.outbox_no_subject
import com.verdenroz.corvid.feature.outbox.resources.outbox_previous_key_detail
import com.verdenroz.corvid.feature.outbox.resources.outbox_reason_alias_gone
import com.verdenroz.corvid.feature.outbox.resources.outbox_reason_forbidden
import com.verdenroz.corvid.feature.outbox.resources.outbox_reason_invalid
import com.verdenroz.corvid.feature.outbox.resources.outbox_reason_refused
import com.verdenroz.corvid.feature.outbox.resources.outbox_send_again
import com.verdenroz.corvid.feature.outbox.resources.outbox_send_again_named
import com.verdenroz.corvid.feature.outbox.resources.outbox_sent_at
import com.verdenroz.corvid.feature.outbox.resources.outbox_status_cannot_open
import com.verdenroz.corvid.feature.outbox.resources.outbox_status_decision
import com.verdenroz.corvid.feature.outbox.resources.outbox_status_not_sent
import com.verdenroz.corvid.feature.outbox.resources.outbox_status_sending
import com.verdenroz.corvid.feature.outbox.resources.outbox_status_sent
import com.verdenroz.corvid.feature.outbox.resources.outbox_status_waiting
import com.verdenroz.corvid.feature.outbox.resources.outbox_title
import com.verdenroz.corvid.feature.outbox.resources.outbox_to
import com.verdenroz.corvid.feature.outbox.resources.outbox_try_now
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource

private val PAGE_WIDTH = 640.dp

class OutboxActions(
    val tryNow: () -> Unit,
    val resend: (String) -> Unit,
    val askDiscard: (String) -> Unit,
    val keep: () -> Unit,
    val discard: (String) -> Unit,
)

@Composable
fun OutboxScreen(
    viewModel: OutboxViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val actions =
        remember(
            viewModel,
        ) { OutboxActions(viewModel::tryNow, viewModel::resend, viewModel::askDiscard, viewModel::keep, viewModel::discard) }
    OutboxScreen(state, actions, onBack)
}

@Composable
fun OutboxScreen(
    state: OutboxListState,
    actions: OutboxActions,
    onBack: () -> Unit,
) {
    val gutter = CorvidSpacing.gutter(LocalWideLayout.current)
    Column(Modifier.fillMaxSize().background(CorvidTheme.colors.surface)) {
        PageHeader(stringResource(Res.string.outbox_title), stringResource(Res.string.outbox_back), onBack)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = gutter, end = gutter, top = 24.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    stringResource(Res.string.outbox_detail),
                    style = CorvidTheme.type.body,
                    color = CorvidTheme.colors.inkMuted,
                    modifier = Modifier.widthIn(max = PAGE_WIDTH),
                )
            }
            if (state.rows.isEmpty()) {
                item { Text(stringResource(Res.string.outbox_empty), style = CorvidTheme.type.body, color = CorvidTheme.colors.inkMuted) }
            } else {
                if (state.waiting) item { QuietLink(stringResource(Res.string.outbox_try_now), actions.tryNow) }
                item { HorizontalDivider(Modifier.widthIn(max = PAGE_WIDTH), color = CorvidTheme.colors.line) }
                items(state.rows, key = { it.id }) { row ->
                    Column(Modifier.widthIn(max = PAGE_WIDTH)) {
                        OutboxItemRow(row, state.confirming == row.id, actions)
                        HorizontalDivider(color = CorvidTheme.colors.line)
                    }
                }
            }
        }
    }
}

@Composable
private fun OutboxItemRow(
    row: OutboxRow,
    confirming: Boolean,
    actions: OutboxActions,
) {
    val name = row.subject?.ifBlank { null } ?: row.recipients ?: stringResource(Res.string.outbox_message)
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(row.status.title()), style = CorvidTheme.type.name, color = CorvidTheme.colors.ink)
        row.recipients?.let {
            Text(
                stringResource(Res.string.outbox_to, it),
                style = CorvidTheme.type.meta,
                color = CorvidTheme.colors.inkMuted,
            )
        }
        row.subject?.let {
            Text(
                it.ifBlank { stringResource(Res.string.outbox_no_subject) },
                style = CorvidTheme.type.meta,
                color = CorvidTheme.colors.inkMuted,
            )
        }
        row.sentAt?.let {
            val at = it.toLocalDateTime(TimeZone.currentSystemDefault())
            val time = "${at.date} ${at.hour.toString().padStart(2, '0')}:${at.minute.toString().padStart(2, '0')}"
            Text(stringResource(Res.string.outbox_sent_at, time), style = CorvidTheme.type.meta, color = CorvidTheme.colors.inkMuted)
        }
        row.detail()?.let { Text(stringResource(it), style = CorvidTheme.type.meta, color = CorvidTheme.colors.ink) }
        if (confirming) {
            DiscardQuestion(
                stringResource(row.discardQuestion()),
                { actions.discard(row.id) },
                actions.keep,
                confirmLabel = stringResource(Res.string.outbox_discard),
                cancelLabel = stringResource(Res.string.outbox_keep),
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (row.canResend) {
                    val label = stringResource(Res.string.outbox_send_again_named, name)
                    QuietLink(
                        stringResource(Res.string.outbox_send_again),
                        onClick = { actions.resend(row.id) },
                        modifier = Modifier.semantics { contentDescription = label },
                    )
                }
                if (row.canDiscard) {
                    val label = stringResource(Res.string.outbox_discard_named, name)
                    QuietLink(
                        stringResource(Res.string.outbox_discard),
                        onClick = { actions.askDiscard(row.id) },
                        modifier = Modifier.semantics { contentDescription = label },
                    )
                }
            }
        }
    }
}

private fun OutboxStatus.title() =
    when (this) {
        OutboxStatus.Waiting -> Res.string.outbox_status_waiting
        OutboxStatus.Sending -> Res.string.outbox_status_sending
        OutboxStatus.NeedsDecision -> Res.string.outbox_status_decision
        OutboxStatus.NotSent -> Res.string.outbox_status_not_sent
        OutboxStatus.CannotOpen, OutboxStatus.SealedToPreviousKey -> Res.string.outbox_status_cannot_open
        OutboxStatus.Sent -> Res.string.outbox_status_sent
    }

private fun OutboxRow.detail() =
    when (status) {
        OutboxStatus.NeedsDecision -> Res.string.outbox_decision_detail
        OutboxStatus.CannotOpen -> Res.string.outbox_cannot_open_detail
        OutboxStatus.SealedToPreviousKey -> Res.string.outbox_previous_key_detail
        OutboxStatus.NotSent ->
            when (rejection) {
                RejectionCategory.Refused -> Res.string.outbox_reason_refused
                RejectionCategory.Forbidden -> Res.string.outbox_reason_forbidden
                RejectionCategory.Invalid -> Res.string.outbox_reason_invalid
                RejectionCategory.AliasGone -> Res.string.outbox_reason_alias_gone
                null -> null
            }
        else -> null
    }

private fun OutboxRow.discardQuestion() =
    when {
        mayHaveBeenSent -> Res.string.outbox_discard_question_maybe_sent
        status == OutboxStatus.CannotOpen || status == OutboxStatus.SealedToPreviousKey -> Res.string.outbox_discard_question_unopenable
        else -> Res.string.outbox_discard_question
    }
