package com.verdenroz.corvid.feature.mailbox

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.designsystem.CorvidSpacing
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.CorvidIcons
import com.verdenroz.corvid.core.designsystem.component.PillButton
import com.verdenroz.corvid.core.model.Conversation
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.core.ui.LocalWideLayout
import com.verdenroz.corvid.core.ui.QuietPane
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.ledger_empty_archive
import com.verdenroz.corvid.feature.mailbox.resources.ledger_empty_drafts
import com.verdenroz.corvid.feature.mailbox.resources.ledger_empty_inbox
import com.verdenroz.corvid.feature.mailbox.resources.ledger_empty_junk
import com.verdenroz.corvid.feature.mailbox.resources.ledger_empty_sent
import com.verdenroz.corvid.feature.mailbox.resources.ledger_empty_starred
import com.verdenroz.corvid.feature.mailbox.resources.ledger_empty_trash
import com.verdenroz.corvid.feature.mailbox.resources.selection_all
import com.verdenroz.corvid.feature.mailbox.resources.selection_clear
import com.verdenroz.corvid.feature.mailbox.resources.selection_count
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

private const val LOAD_AHEAD = 8

@Composable
fun LedgerScreen(
    viewModel: LedgerViewModel,
    actions: MailActions,
    tab: LedgerTab,
    onOpenEmail: (String) -> Unit,
    onOpenSender: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LedgerContent(viewModel, actions, stringResource(tab.emptyText()), onOpenEmail, onOpenSender, modifier)
}

internal suspend fun LedgerViewModel.refreshAndWait() {
    refresh()?.join()
}

@Composable
internal fun LedgerContent(
    viewModel: LedgerViewModel,
    actions: MailActions,
    emptyText: String,
    onOpenEmail: (String) -> Unit,
    onOpenSender: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val selected by viewModel.selected.collectAsState()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var doomed by remember { mutableStateOf<ActionTarget?>(null) }
    val kind = viewModel.source.kind
    val menu = rememberMoveMenu(actions)
    val onAction: (ConversationAction, ActionTarget) -> Unit = { action, target ->
        if (action == ConversationAction.Destroy) {
            doomed = target
        } else {
            actions.perform(action, target)
            if (action.leavesList) viewModel.clearSelection()
        }
    }
    val onMove: (MoveFolder, ActionTarget) -> Unit = { folder, target ->
        actions.moveTo(target.all, target.conversations, folder)
        viewModel.clearSelection()
    }
    Column(modifier.fillMaxSize()) {
        doomed?.let { target ->
            DestroyQuestion(
                onConfirm = {
                    actions.perform(ConversationAction.Destroy, target)
                    viewModel.clearSelection()
                    doomed = null
                },
                onCancel = { doomed = null },
            )
        }
        val conversations = (state as? LedgerUiState.Loaded)?.conversations.orEmpty()
        if (selected.isNotEmpty()) {
            val ticked = remember(conversations, selected) { conversations.filter { it.latest.id in selected } }
            SelectionBar(
                actionsFor(kind, ticked.target()),
                ticked.size,
                allSelected = ticked.size == conversations.size,
                onSelectAll = viewModel::selectAll,
                onClear = viewModel::clearSelection,
                menu = menu,
                onMove = { onMove(it, ticked.target()) },
                onAction = { onAction(it, ticked.target()) },
            )
        } else if (conversations.isNotEmpty()) {
            (viewModel.source as? LedgerSource.Tab)?.tab?.let { tab -> EmptyMailboxBar(tab) { actions.empty(it) } }
        }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    try {
                        viewModel.refreshAndWait()
                    } finally {
                        refreshing = false
                    }
                }
            },
            modifier = Modifier.weight(1f),
        ) {
            when (val current = state) {
                is LedgerUiState.Loading -> Unit
                is LedgerUiState.Failed -> QuietPane(current.message.asString())
                is LedgerUiState.Loaded ->
                    if (current.conversations.isEmpty()) {
                        LazyColumn(Modifier.fillMaxSize()) { item { QuietPane(emptyText, Modifier.fillParentMaxSize()) } }
                    } else {
                        Ledger(
                            current,
                            kind,
                            selected,
                            viewModel::toggleSelected,
                            onOpenEmail,
                            onOpenSender,
                            onAction,
                            menu,
                            onMove,
                            viewModel::loadMore,
                        )
                    }
            }
        }
    }
}

@Composable
private fun SelectionBar(
    actions: List<ConversationAction>,
    count: Int,
    allSelected: Boolean,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    menu: MoveMenu,
    onMove: (MoveFolder) -> Unit,
    onAction: (ConversationAction) -> Unit,
) {
    val colors = CorvidTheme.colors
    Column(Modifier.fillMaxWidth().background(colors.rail)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        ) {
            ActionIcon(stringResource(Res.string.selection_clear), CorvidIcons.Close, onClear)
            Text(
                stringResource(Res.string.selection_count, count),
                style = CorvidTheme.type.name,
                fontWeight = FontWeight.Bold,
                color = colors.ink,
            )
            if (!allSelected) PillButton(stringResource(Res.string.selection_all), null, onSelectAll, height = 30.dp)
            Spacer(Modifier.weight(1f))
            ConversationActionRow(actions, menu, onMove, onAction = onAction)
        }
        HorizontalDivider(color = colors.line)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Ledger(
    state: LedgerUiState.Loaded,
    kind: ListKind,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onOpenEmail: (String) -> Unit,
    onOpenSender: (String) -> Unit,
    onAction: (ConversationAction, ActionTarget) -> Unit,
    menu: MoveMenu,
    onMove: (MoveFolder, ActionTarget) -> Unit,
    onLoadMore: () -> Unit,
) {
    val conversations = state.conversations
    val zone = remember { TimeZone.currentSystemDefault() }
    val today = Clock.System.now().toLocalDateTime(zone).date
    val days = remember(conversations, today) { ledgerDays(conversations, zone, today) }
    val listState = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 } }
    LaunchedEffect(nearEnd, conversations.size) {
        if (state.canLoadMore && nearEnd >= listState.layoutInfo.totalItemsCount - LOAD_AHEAD) onLoadMore()
    }
    val wide = LocalWideLayout.current
    val selecting = selected.isNotEmpty()
    val onRowAction = remember(onAction) { { action: ConversationAction, row: Conversation -> onAction(action, listOf(row).target()) } }
    val onRowMove = remember(onMove) { { folder: MoveFolder, row: Conversation -> onMove(folder, listOf(row).target()) } }
    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 96.dp), modifier = Modifier.fillMaxSize()) {
        days.forEach { day ->
            stickyHeader(key = day.date.toString()) { DayHeader(day, wide) }
            items(day.conversations, key = { it.latest.id }) { conversation ->
                LedgerRow(
                    conversation,
                    zone,
                    wide,
                    remember(conversation, kind) { actionsFor(kind, listOf(conversation).target()) },
                    selected = conversation.latest.id in selected,
                    selecting = selecting,
                    onToggle = onToggle,
                    onOpen = onOpenEmail,
                    onAction = onRowAction,
                    menu = menu,
                    onMove = onRowMove,
                    onOpenSender = onOpenSender,
                )
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
        LedgerTab.Archive -> Res.string.ledger_empty_archive
        LedgerTab.Junk -> Res.string.ledger_empty_junk
        LedgerTab.Trash -> Res.string.ledger_empty_trash
    }
