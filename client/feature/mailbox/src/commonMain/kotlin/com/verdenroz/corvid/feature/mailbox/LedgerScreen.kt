package com.verdenroz.corvid.feature.mailbox

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.designsystem.CorvidSpacing
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.core.ui.LocalWideLayout
import com.verdenroz.corvid.core.ui.QuietPane
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.ledger_empty_drafts
import com.verdenroz.corvid.feature.mailbox.resources.ledger_empty_inbox
import com.verdenroz.corvid.feature.mailbox.resources.ledger_empty_sent
import com.verdenroz.corvid.feature.mailbox.resources.ledger_empty_starred
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

private const val LOAD_AHEAD = 8

@Composable
fun LedgerScreen(
    viewModel: LedgerViewModel,
    tab: LedgerTab,
    onOpenEmail: (String) -> Unit,
    onOpenSender: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()

    LedgerContent(
        state,
        stringResource(tab.emptyText()),
        onOpenEmail,
        onOpenSender,
        viewModel::loadMore,
        viewModel::refreshAndWait,
        modifier,
    )
}

internal suspend fun LedgerViewModel.refreshAndWait() {
    refresh()?.join()
}

@Composable
internal fun LedgerContent(
    state: LedgerUiState,
    emptyText: String,
    onOpenEmail: (String) -> Unit,
    onOpenSender: (String) -> Unit,
    onLoadMore: () -> Unit,
    onRefresh: suspend () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                try {
                    onRefresh()
                } finally {
                    refreshing = false
                }
            }
        },
        modifier = modifier.fillMaxSize(),
    ) {
        when (state) {
            is LedgerUiState.Loading -> Unit
            is LedgerUiState.Failed -> QuietPane(state.message.asString())
            is LedgerUiState.Loaded ->
                if (state.conversations.isEmpty()) {
                    LazyColumn(Modifier.fillMaxSize()) { item { QuietPane(emptyText, Modifier.fillParentMaxSize()) } }
                } else {
                    Ledger(state, onOpenEmail, onOpenSender, onLoadMore)
                }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Ledger(
    state: LedgerUiState.Loaded,
    onOpenEmail: (String) -> Unit,
    onOpenSender: (String) -> Unit,
    onLoadMore: () -> Unit,
) {
    val zone = remember { TimeZone.currentSystemDefault() }
    val today = Clock.System.now().toLocalDateTime(zone).date
    val days = remember(state.conversations, today) { ledgerDays(state.conversations, zone, today) }
    val listState = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 } }
    LaunchedEffect(nearEnd, state.conversations.size) {
        if (state.canLoadMore && nearEnd >= listState.layoutInfo.totalItemsCount - LOAD_AHEAD) onLoadMore()
    }
    val wide = LocalWideLayout.current
    Box {
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 96.dp), modifier = Modifier.fillMaxSize()) {
            days.forEach { day ->
                stickyHeader(key = day.date.toString()) { DayHeader(day, wide) }
                items(day.conversations, key = { it.latest.id }) { conversation ->
                    LedgerRow(conversation, zone, wide, onClick = { onOpenEmail(conversation.latest.id) }, onOpenSender = onOpenSender)
                }
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
            modifier = Modifier.padding(start = CorvidSpacing.gutter(wide), end = 16.dp, top = 18.dp, bottom = 8.dp),
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

private fun LedgerTab.emptyText() =
    when (this) {
        LedgerTab.Inbox -> Res.string.ledger_empty_inbox
        LedgerTab.Starred -> Res.string.ledger_empty_starred
        LedgerTab.Sent -> Res.string.ledger_empty_sent
        LedgerTab.Drafts -> Res.string.ledger_empty_drafts
    }
