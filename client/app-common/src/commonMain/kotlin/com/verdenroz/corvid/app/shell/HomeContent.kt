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
import com.verdenroz.corvid.appcommon.resources.placeholder_labels
import com.verdenroz.corvid.appcommon.resources.placeholder_people
import com.verdenroz.corvid.appcommon.resources.placeholder_sources
import com.verdenroz.corvid.appcommon.resources.write
import com.verdenroz.corvid.core.designsystem.CorvidShape
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.CorvidIcons
import com.verdenroz.corvid.core.ui.QuietPane
import com.verdenroz.corvid.feature.mailbox.LedgerScreen
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun HomeContent(
    tab: HomeTab,
    ledgers: Ledgers,
    onOpenEmail: (String) -> Unit,
    onOpenSender: (String) -> Unit,
    onWrite: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        val ledger = tab.ledger
        if (ledger == null) {
            NotBuilt(tab)
        } else {
            LedgerScreen(ledgers.of(ledger), ledgers.actions, ledger, onOpenEmail, onOpenSender)
        }
        WriteButton(onWrite, Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 22.dp))
    }
}

@Composable
private fun NotBuilt(tab: HomeTab) {
    val text =
        when (tab) {
            HomeTab.People -> Res.string.placeholder_people
            HomeTab.Sources -> Res.string.placeholder_sources
            else -> Res.string.placeholder_labels
        }
    QuietPane(stringResource(text))
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
            modifier = Modifier.height(40.dp).padding(start = 12.dp, end = 16.dp),
        ) {
            Icon(CorvidIcons.Pencil, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(Res.string.write), style = CorvidTheme.type.action)
        }
    }
}
