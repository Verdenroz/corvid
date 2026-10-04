package com.verdenroz.corvid.feature.mailbox

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.search_no_results
import org.jetbrains.compose.resources.stringResource

/** The ledger list for one query; [viewModel] is null while the query is blank, so nothing is fetched. */
@Composable
fun SearchScreen(
    viewModel: LedgerViewModel?,
    query: String,
    onOpenEmail: (String) -> Unit,
    onOpenSender: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (viewModel == null) {
        Box(modifier.fillMaxSize())
        return
    }
    val state by viewModel.state.collectAsState()
    LedgerContent(state, stringResource(Res.string.search_no_results, query), onOpenEmail, onOpenSender, viewModel::loadMore, modifier)
}
