package com.verdenroz.corvid.feature.mailbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.data.SearchQuery
import com.verdenroz.corvid.core.data.SearchScope
import com.verdenroz.corvid.core.data.searchAnywhere
import com.verdenroz.corvid.core.designsystem.CorvidShape
import com.verdenroz.corvid.core.designsystem.CorvidSpacing
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.LocalWideLayout
import com.verdenroz.corvid.core.designsystem.focusRing
import com.verdenroz.corvid.core.model.EmailSummary
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.search_exclude_discarded
import com.verdenroz.corvid.feature.mailbox.resources.search_hint_examples
import com.verdenroz.corvid.feature.mailbox.resources.search_hint_scope
import com.verdenroz.corvid.feature.mailbox.resources.search_include_discarded
import com.verdenroz.corvid.feature.mailbox.resources.search_no_results
import com.verdenroz.corvid.feature.mailbox.resources.search_results
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/**
 * The ledger list for one query, under its count; [viewModel] is null while the query is blank, so nothing is
 * fetched and the operators are shown instead. [onQuery] replaces the query in the search field.
 */
@Composable
fun SearchScreen(
    viewModel: LedgerViewModel?,
    actions: MailActions,
    query: String,
    onOpenEmail: (EmailSummary) -> Unit,
    onOpenSender: (String) -> Unit,
    onQuery: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (viewModel == null) {
        SearchHints(modifier)
        return
    }
    val total by viewModel.total.collectAsState()
    val scope = remember(query) { SearchQuery.parse(query).scope }
    Column(modifier.fillMaxSize()) {
        SearchSummary(total, scope, onScope = { onQuery(searchAnywhere(query, anywhere = it)) })
        LedgerContent(
            viewModel,
            actions,
            stringResource(Res.string.search_no_results, query),
            onOpenEmail,
            onOpenSender,
            Modifier.weight(1f),
        )
    }
}

/** The match count, and the switch between leaving Trash and Junk out and searching them too. */
@Composable
private fun SearchSummary(
    total: Int?,
    scope: SearchScope,
    onScope: (anywhere: Boolean) -> Unit,
) {
    val colors = CorvidTheme.colors
    val count = total?.takeIf { it > 0 }
    if (count == null && scope == SearchScope.Named) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = CorvidSpacing.gutter(LocalWideLayout.current), vertical = 6.dp),
    ) {
        Text(
            count?.let { pluralStringResource(Res.plurals.search_results, it, it) }.orEmpty(),
            style = CorvidTheme.type.metaNumeric,
            color = colors.inkMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (scope != SearchScope.Named) {
            val anywhere = scope == SearchScope.Anywhere
            val label = stringResource(if (anywhere) Res.string.search_exclude_discarded else Res.string.search_include_discarded)
            Text(
                label,
                style = CorvidTheme.type.meta,
                color = colors.ink,
                maxLines = 1,
                modifier =
                    Modifier
                        .focusRing(CorvidShape.small)
                        .clickable(role = Role.Button) { onScope(!anywhere) }
                        .padding(vertical = 6.dp),
            )
        }
    }
    HorizontalDivider(color = colors.line)
}

@Composable
private fun SearchHints(modifier: Modifier = Modifier) {
    val colors = CorvidTheme.colors
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.fillMaxSize().padding(horizontal = CorvidSpacing.gutter(LocalWideLayout.current), vertical = 14.dp),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.widthIn(max = 520.dp),
        ) {
            // Unbordered, so the examples read as text to type rather than chips to tap.
            stringArrayResource(Res.array.search_hint_examples).forEach { example ->
                Text(example, style = CorvidTheme.type.chip, color = colors.inkMuted)
            }
        }
        Text(stringResource(Res.string.search_hint_scope), style = CorvidTheme.type.meta, color = colors.inkMuted)
    }
}
