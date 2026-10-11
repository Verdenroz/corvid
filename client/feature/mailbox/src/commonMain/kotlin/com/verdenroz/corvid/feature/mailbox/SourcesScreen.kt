package com.verdenroz.corvid.feature.mailbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.data.Source
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.model.Seal
import com.verdenroz.corvid.core.model.SenderKind
import com.verdenroz.corvid.core.ui.provenance.SenderAvatar
import com.verdenroz.corvid.core.ui.provenance.TrackerCount
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.directory_failed
import com.verdenroz.corvid.feature.mailbox.resources.directory_messages
import com.verdenroz.corvid.feature.mailbox.resources.directory_signed
import com.verdenroz.corvid.feature.mailbox.resources.directory_signed_by
import com.verdenroz.corvid.feature.mailbox.resources.directory_source_detail
import com.verdenroz.corvid.feature.mailbox.resources.directory_unsigned
import com.verdenroz.corvid.feature.mailbox.resources.sources_empty
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Services, lists and the account's own server, busiest first; a row opens the card that unsubscribes or makes a filter. */
@Composable
fun SourcesScreen(
    viewModel: DirectoryViewModel,
    onOpenSender: (String) -> Unit,
) {
    DirectoryList(viewModel, stringResource(Res.string.sources_empty), isEmpty = { it.sources.isEmpty() }) { directory ->
        items(directory.sources, key = { it.address.email.lowercase() }) { source ->
            val name = source.address.displayName()
            val sender = SenderKind.Source(source.kind)
            DirectoryRow(
                name = name,
                detail = stringResource(Res.string.directory_source_detail, sender.sourceLabel().orEmpty(), sealText(source.seal)),
                detailColor = if (source.seal is Seal.Failed) CorvidTheme.colors.danger else CorvidTheme.colors.inkMuted,
                last = source.last,
                onClick = { onOpenSender(source.address.email) },
                leading = { SenderAvatar(name, sender, source.seal, size = 40.dp) },
            ) { Tally(source) }
        }
    }
}

@Composable
private fun Tally(source: Source) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (source.trackersStripped > 0) TrackerCount(source.trackersStripped)
        Text(
            pluralStringResource(Res.plurals.directory_messages, source.received, source.received),
            style = CorvidTheme.type.metaNumeric,
            color = CorvidTheme.colors.inkFaint,
        )
    }
}

@Composable
private fun sealText(seal: Seal): String =
    when (seal) {
        is Seal.Signed ->
            seal.signer?.let { stringResource(Res.string.directory_signed_by, it) } ?: stringResource(Res.string.directory_signed)
        is Seal.Failed -> stringResource(Res.string.directory_failed)
        Seal.Unsigned -> stringResource(Res.string.directory_unsigned)
    }
