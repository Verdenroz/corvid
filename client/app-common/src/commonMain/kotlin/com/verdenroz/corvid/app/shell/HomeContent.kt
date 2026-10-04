package com.verdenroz.corvid.app.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.app.HomeTab
import com.verdenroz.corvid.appcommon.resources.Res
import com.verdenroz.corvid.appcommon.resources.placeholder_labels
import com.verdenroz.corvid.appcommon.resources.placeholder_people
import com.verdenroz.corvid.appcommon.resources.placeholder_sources
import com.verdenroz.corvid.appcommon.resources.write
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.CorvidIcons
import com.verdenroz.corvid.feature.mailbox.LedgerScreen
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun HomeContent(
    tab: HomeTab,
    repository: MailboxRepository,
    onOpenEmail: (String) -> Unit,
    onOpenSender: (String) -> Unit,
    onWrite: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        val ledger = tab.ledger
        if (ledger == null) {
            NotBuilt(tab)
        } else {
            LedgerScreen(repository, ledger, onOpenEmail, onOpenSender)
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
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            stringResource(text),
            style = CorvidTheme.type.body,
            color = CorvidTheme.colors.inkMuted,
            modifier = Modifier.widthIn(max = 420.dp),
        )
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
        shape = RoundedCornerShape(16.dp),
        color = colors.accent,
        contentColor = colors.onAccent,
        shadowElevation = 6.dp,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.height(48.dp).padding(start = 16.dp, end = 20.dp),
        ) {
            Icon(CorvidIcons.Pencil, contentDescription = null)
            Text(stringResource(Res.string.write), style = CorvidTheme.type.action)
        }
    }
}
