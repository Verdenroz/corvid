package com.verdenroz.corvid.feature.mailbox

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.search_no_results
import org.jetbrains.compose.resources.stringResource

/** The ledger list for one query; [viewModel] is null while the query is blank, so nothing is fetched. */
@Composable
fun SearchScreen(
    viewModel: LedgerViewModel?,
    actions: MailActions,
    query: String,
    onOpenEmail: (String) -> Unit,
    onOpenSender: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (viewModel == null) {
        Box(modifier.fillMaxSize())
        return
    }
    LedgerContent(viewModel, actions, stringResource(Res.string.search_no_results, query), onOpenEmail, onOpenSender, modifier)
}
