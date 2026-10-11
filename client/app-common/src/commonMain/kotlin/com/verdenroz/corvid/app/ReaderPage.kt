package com.verdenroz.corvid.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.model.ComposeIntent
import com.verdenroz.corvid.feature.mailbox.LedgerSource
import com.verdenroz.corvid.feature.mailbox.LedgerTab
import com.verdenroz.corvid.feature.mailbox.LedgerUiState
import com.verdenroz.corvid.feature.mailbox.LedgerViewModel
import com.verdenroz.corvid.feature.mailbox.MailActions
import com.verdenroz.corvid.feature.mailbox.ReaderScreen
import com.verdenroz.corvid.feature.mailbox.neighboursOf

internal fun searchLedger(
    models: EntryModels,
    entryKey: String,
    mailbox: MailboxRepository,
    query: String,
): LedgerViewModel = models.of(entryKey, LedgerViewModel::class) { LedgerViewModel(mailbox, LedgerSource.Search(query)) }

/** The list a reader at [index] was opened from: the entry under it, when that is a home ledger or a search. */
internal fun listUnder(
    index: Int,
    navigator: Navigator,
    models: EntryModels,
    ledgers: Ledgers,
    mailbox: MailboxRepository,
): LedgerViewModel? =
    when (val under = if (index > 0) navigator[index - 1] else null) {
        is Route.Home -> under.tab.ledger?.takeIf { it != LedgerTab.Drafts }?.let(ledgers::of)
        is Route.Search -> under.query.takeIf { it.isNotBlank() }?.let { searchLedger(models, navigator.entryKey(index - 1), mailbox, it) }
        else -> null
    }

/** Moving between conversations replaces the reader, so Back still returns to the list rather than the last one read. */
@Composable
internal fun ReaderPage(
    route: Route.Reader,
    list: LedgerViewModel?,
    mailbox: MailboxRepository,
    actions: MailActions,
    navigator: Navigator,
    onCompose: (ComposeIntent) -> Unit,
    onOpenSender: (String) -> Unit,
) {
    val loaded = list?.state?.collectAsState()?.value as? LedgerUiState.Loaded
    val neighbours = loaded?.neighboursOf(route.emailId)
    val atLoadedEnd = neighbours != null && neighbours.older == null && loaded.canLoadMore
    LaunchedEffect(atLoadedEnd) { if (atLoadedEnd) list.loadMore() }
    ReaderScreen(
        mailbox,
        actions,
        route.emailId,
        neighbours,
        onOpen = { navigator.replace(Route.Reader(it)) },
        onBack = navigator::back,
        onCompose = onCompose,
        onOpenSender = onOpenSender,
    )
}
