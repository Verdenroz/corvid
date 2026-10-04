package com.verdenroz.corvid.feature.mailbox

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.ledger_empty_drafts
import com.verdenroz.corvid.feature.mailbox.resources.ledger_empty_inbox
import com.verdenroz.corvid.feature.mailbox.resources.ledger_empty_sent
import com.verdenroz.corvid.feature.mailbox.resources.ledger_empty_starred
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

/** Below this width a row stacks the sender over the subject. */
val WIDE_LEDGER = 720.dp

private const val LOAD_AHEAD = 8

@Composable
fun LedgerScreen(
    repository: MailboxRepository,
    tab: LedgerTab,
    onOpenEmail: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = remember(repository, tab) { LedgerViewModel(repository, tab) }
    val state by viewModel.state.collectAsState()

    Box(modifier.fillMaxSize()) {
        when (val current = state) {
            is LedgerUiState.Loading -> Unit
            is LedgerUiState.Failed -> Quiet(current.message.asString())
            is LedgerUiState.Loaded ->
                if (current.emails.isEmpty()) {
                    Quiet(stringResource(tab.emptyText()))
                } else {
                    Ledger(current, onOpenEmail, viewModel::loadMore)
                }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Ledger(
    state: LedgerUiState.Loaded,
    onOpenEmail: (String) -> Unit,
    onLoadMore: () -> Unit,
) {
    val zone = remember { TimeZone.currentSystemDefault() }
    val today = Clock.System.now().toLocalDateTime(zone).date
    val days = remember(state.emails, today) { ledgerDays(state.emails, zone, today) }
    val listState = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 } }
    LaunchedEffect(nearEnd, state.emails.size) {
        if (state.canLoadMore && nearEnd >= listState.layoutInfo.totalItemsCount - LOAD_AHEAD) onLoadMore()
    }
    BoxWithConstraints {
        val wide = maxWidth >= WIDE_LEDGER
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 96.dp), modifier = Modifier.fillMaxSize()) {
            days.forEach { day ->
                stickyHeader(key = day.date.toString()) { DayHeader(day, wide) }
                items(day.emails, key = { it.id }) { email -> LedgerRow(email, zone, wide, onClick = { onOpenEmail(email.id) }) }
            }
        }
    }
}

@Composable
private fun DayHeader(
    day: LedgerDay,
    wide: Boolean,
) {
    val colors = CorvidTheme.colors
    Column(Modifier.fillMaxWidth().background(colors.surface)) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(start = if (wide) 24.dp else 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp),
        ) {
            Text(day.heading.title.asString(), style = CorvidTheme.type.dayHeader)
            Text(
                day.heading.detail.asString(),
                style = CorvidTheme.type.meta,
                color = colors.inkFaint,
                modifier = Modifier.padding(bottom = 1.dp),
            )
        }
        HorizontalDivider(color = colors.line)
    }
}

@Composable
private fun Quiet(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = CorvidTheme.type.body, color = CorvidTheme.colors.inkMuted)
    }
}

private fun LedgerTab.emptyText() =
    when (this) {
        LedgerTab.Inbox -> Res.string.ledger_empty_inbox
        LedgerTab.Starred -> Res.string.ledger_empty_starred
        LedgerTab.Sent -> Res.string.ledger_empty_sent
        LedgerTab.Drafts -> Res.string.ledger_empty_drafts
    }
