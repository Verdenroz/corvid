package com.verdenroz.corvid.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import com.verdenroz.corvid.core.data.AttachmentFiles
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.data.SummaryRepository
import com.verdenroz.corvid.feature.mailbox.LedgerUiState
import com.verdenroz.corvid.feature.mailbox.LedgerViewModel
import com.verdenroz.corvid.feature.mailbox.ReaderScreen
import com.verdenroz.corvid.feature.mailbox.ReaderViewModel
import com.verdenroz.corvid.feature.mailbox.kind
import com.verdenroz.corvid.feature.mailbox.neighboursOf

/** The list a reader at [index] was opened from: the entry under it, when that is a home ledger or a search. */
internal fun listUnder(
    index: Int,
    navigator: Navigator,
    models: EntryModels,
    ledgers: Ledgers,
): LedgerViewModel? =
    when (val under = if (index > 0) navigator[index - 1] else null) {
        is Route.Home -> under.tab.ledger?.let(ledgers::of)
        is Route.Search -> under.query.takeIf { it.isNotBlank() }?.let { ledgers.search(models, navigator.entryKey(index - 1), it) }
        else -> null
    }

/**
 * Moving between conversations replaces the reader, so Back still returns to the list rather than the last one read.
 * The reader takes the kind of the list under it, so it offers what that list's row offered.
 */
@Composable
internal fun ReaderPage(
    route: Route.Reader,
    index: Int,
    entryKey: String,
    models: EntryModels,
    mailbox: MailboxRepository,
    summaries: SummaryRepository,
    files: AttachmentFiles,
    ledgers: Ledgers,
    navigator: Navigator,
) {
    val list = listUnder(index, navigator, models, ledgers)
    val viewModel = models.of(entryKey, ReaderViewModel::class) { ReaderViewModel(mailbox, summaries, route.emailId, list?.source?.kind) }
    val loaded = list?.state?.collectAsState()?.value as? LedgerUiState.Loaded
    val neighbours = loaded?.neighboursOf(route.emailId)
    val atLoadedEnd = neighbours != null && neighbours.older == null && loaded.canLoadMore
    LaunchedEffect(atLoadedEnd) { if (atLoadedEnd) list.loadMore() }
    ReaderScreen(
        viewModel,
        mailbox,
        files,
        ledgers.actions,
        neighbours,
        onOpen = { navigator.replace(Route.Reader(it)) },
        onBack = navigator::back,
        onCompose = { navigator.push(Route.Compose(it)) },
        onOpenSender = { navigator.push(Route.Sender(it)) },
    )
}
