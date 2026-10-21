package com.verdenroz.corvid.app.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.app.HomeTab
import com.verdenroz.corvid.app.Ledgers
import com.verdenroz.corvid.appcommon.resources.Res
import com.verdenroz.corvid.appcommon.resources.write
import com.verdenroz.corvid.core.designsystem.CorvidFloating
import com.verdenroz.corvid.core.designsystem.CorvidShape
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.LocalWideLayout
import com.verdenroz.corvid.core.designsystem.component.CorvidIcons
import com.verdenroz.corvid.core.model.EmailSummary
import com.verdenroz.corvid.feature.mailbox.LabelsScreen
import com.verdenroz.corvid.feature.mailbox.LedgerScreen
import com.verdenroz.corvid.feature.mailbox.LedgerTab
import com.verdenroz.corvid.feature.mailbox.OvernightOffer
import com.verdenroz.corvid.feature.mailbox.PeopleScreen
import com.verdenroz.corvid.feature.mailbox.SourcesScreen
import org.jetbrains.compose.resources.stringResource

/** Narrow, Write floats over the list; wide, it heads the sidebar. */
@Composable
internal fun HomeContent(
    tab: HomeTab,
    ledgers: Ledgers,
    overnight: OvernightOffer?,
    onOpenEmail: (EmailSummary) -> Unit,
    onOpenSender: (String) -> Unit,
    onSearch: (String) -> Unit,
    onWrite: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        val ledger = tab.ledger
        when {
            ledger != null ->
                LedgerScreen(
                    ledgers.of(ledger),
                    ledgers.actions,
                    ledger,
                    onOpenEmail,
                    onOpenSender,
                    overnight = overnight.takeIf { ledger == LedgerTab.Inbox },
                )
            tab == HomeTab.People -> PeopleScreen(ledgers.directory, onOpenSender)
            tab == HomeTab.Sources -> SourcesScreen(ledgers.directory, onOpenSender)
            else -> LabelsScreen(ledgers.directory, onSearch)
        }
        if (!LocalWideLayout.current) {
            WriteButton(
                onWrite,
                Modifier.align(Alignment.BottomEnd).padding(end = CorvidFloating.writeEnd, bottom = CorvidFloating.writeBottom),
            )
        }
    }
}

@Composable
private fun WriteButton(
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val colors = CorvidTheme.colors
    Surface(
        onClick = onClick,
        shape = CorvidShape.medium,
        color = colors.ink,
        contentColor = colors.surface,
        shadowElevation = 3.dp,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.height(CorvidFloating.writeHeight).padding(start = 12.dp, end = 16.dp),
        ) {
            Icon(CorvidIcons.Pencil, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(Res.string.write), style = CorvidTheme.type.action)
        }
    }
}
