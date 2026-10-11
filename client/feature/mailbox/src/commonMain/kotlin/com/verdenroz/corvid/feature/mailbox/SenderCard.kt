package com.verdenroz.corvid.feature.mailbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.data.SenderRepository
import com.verdenroz.corvid.core.designsystem.CorvidSpacing
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.CorvidIcons
import com.verdenroz.corvid.core.designsystem.component.PillButton
import com.verdenroz.corvid.core.designsystem.component.PrimaryButton
import com.verdenroz.corvid.core.model.ComposeIntent
import com.verdenroz.corvid.core.model.EmailSummary
import com.verdenroz.corvid.core.model.SenderKind
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.core.ui.provenance.SenderAvatar
import com.verdenroz.corvid.core.ui.provenance.openUntrusted
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.card_close
import com.verdenroz.corvid.feature.mailbox.resources.card_make_filter
import com.verdenroz.corvid.feature.mailbox.resources.card_recent
import com.verdenroz.corvid.feature.mailbox.resources.card_source_kind
import com.verdenroz.corvid.feature.mailbox.resources.card_unsubscribe
import com.verdenroz.corvid.feature.mailbox.resources.card_write
import com.verdenroz.corvid.feature.mailbox.resources.mailbox_no_subject
import com.verdenroz.corvid.feature.mailbox.resources.mailbox_separator
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

/** What a card can ask the shell to do. */
class SenderCardActions(
    val close: () -> Unit,
    val openEmail: (EmailSummary) -> Unit,
    val compose: (ComposeIntent) -> Unit,
    val makeFilter: () -> Unit,
)

@Composable
fun SenderCard(
    senders: SenderRepository,
    address: String,
    wide: Boolean,
    actions: SenderCardActions,
) {
    val model = remember(address) { SenderCardModel(senders, address) }
    LaunchedEffect(model) { model.load() }
    val state by model.state.collectAsState()
    val latest = state.recent?.firstOrNull()
    val isSource = latest?.provenance?.sender is SenderKind.Source

    Column(Modifier.fillMaxSize().background(CorvidTheme.colors.surface)) {
        Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = if (wide) Arrangement.End else Arrangement.Start) {
            IconButton(onClick = actions.close) {
                Icon(
                    if (wide) CorvidIcons.Close else CorvidIcons.Back,
                    stringResource(Res.string.card_close),
                    tint = CorvidTheme.colors.inkMuted,
                )
            }
        }
        Header(address, latest, wide)
        failedWarning(latest?.provenance?.seal)?.let {
            Text(
                it.asString(),
                style = CorvidTheme.type.meta,
                color = CorvidTheme.colors.onDangerSoft,
                modifier =
                    Modifier.fillMaxWidth().background(
                        CorvidTheme.colors.dangerSoft,
                    ).padding(horizontal = CorvidSpacing.gutter(wide), vertical = 10.dp),
            )
        }
        Facts(state, latest, isSource, wide)
        Actions(address, latest, isSource, wide, actions)
        state.failed?.let {
            Text(it.asString(), style = CorvidTheme.type.meta, color = CorvidTheme.colors.danger, modifier = Modifier.padding(16.dp))
        }
        History(state.recent.orEmpty(), wide, actions.openEmail, Modifier.weight(1f))
    }
}

@Composable
private fun Header(
    address: String,
    latest: EmailSummary?,
    wide: Boolean,
) {
    val colors = CorvidTheme.colors
    val seal = latest?.provenance?.seal
    val sender = latest?.provenance?.sender
    val name = if (latest == null) address else latest.from.displayName()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    val y = size.height - 0.5.dp.toPx()
                    drawLine(colors.line, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                }.padding(start = CorvidSpacing.gutter(wide), end = CorvidSpacing.gutter(wide), bottom = 18.dp),
    ) {
        SenderAvatar(name, sender ?: SenderKind.Person, seal, size = if (wide) 60.dp else 52.dp)
        Column(Modifier.weight(1f)) {
            sender?.sourceLabel()?.let {
                Text(stringResource(Res.string.card_source_kind, it).uppercase(), style = CorvidTheme.type.label, color = colors.inkFaint)
            }
            Text(
                name,
                style =
                    CorvidTheme.type.headline.copy(
                        fontSize = if (wide) CorvidTheme.type.headline.fontSize else CorvidTheme.type.title.fontSize,
                    ),
                maxLines = 2,
            )
            Text(
                latest?.let { senderAddress(address, it.provenance.suspect).asString() } ?: address,
                style = CorvidTheme.type.meta,
                color = if (latest?.provenance?.suspect == true) colors.danger else colors.inkMuted,
            )
        }
    }
}

@Composable
private fun Actions(
    address: String,
    latest: EmailSummary?,
    isSource: Boolean,
    wide: Boolean,
    actions: SenderCardActions,
) {
    val uri = LocalUriHandler.current
    val provenance = latest?.provenance
    val unsubscribe = provenance?.let(::unsubscribe)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = CorvidSpacing.gutter(wide), vertical = 14.dp),
    ) {
        if (isSource) {
            if (provenance != null && unsubscribe != null) {
                PillButton(stringResource(Res.string.card_unsubscribe), CorvidIcons.Unsubscribe, {
                    when (unsubscribe) {
                        is Unsubscribe.Mail -> actions.compose(unsubscribe.intent)
                        is Unsubscribe.Open -> uri.openUntrusted(unsubscribe.url, provenance)
                    }
                })
            }
            PillButton(stringResource(Res.string.card_make_filter), CorvidIcons.Filter, actions.makeFilter)
        } else if (latest?.provenance?.suspect == true) {
            PillButton(stringResource(Res.string.card_write), CorvidIcons.Pencil, { actions.compose(ComposeIntent.To(address)) })
        } else {
            PrimaryButton(
                stringResource(Res.string.card_write),
                onClick = { actions.compose(ComposeIntent.To(address)) },
                icon = CorvidIcons.Pencil,
            )
        }
    }
    HorizontalDivider(color = CorvidTheme.colors.line)
}

@Composable
private fun History(
    recent: List<EmailSummary>,
    wide: Boolean,
    onOpen: (EmailSummary) -> Unit,
    modifier: Modifier,
) {
    val colors = CorvidTheme.colors
    val zone = remember { TimeZone.currentSystemDefault() }
    val today = Clock.System.now().toLocalDateTime(zone).date
    val separator = stringResource(Res.string.mailbox_separator)
    LazyColumn(modifier.fillMaxWidth().padding(horizontal = CorvidSpacing.gutter(wide))) {
        item {
            Text(
                stringResource(Res.string.card_recent).uppercase(),
                style = CorvidTheme.type.label,
                color = colors.inkFaint,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
        }
        items(recent, key = { it.id }) { email ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(email) }.padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    whenArrived(email.receivedAt, zone, today),
                    style = CorvidTheme.type.metaNumeric,
                    color = colors.inkFaint,
                    modifier = Modifier.width(74.dp),
                )
                Text(
                    "${email.subject.ifEmpty { stringResource(Res.string.mailbox_no_subject) }} $separator ${email.preview}",
                    style = CorvidTheme.type.name.copy(fontWeight = FontWeight.Normal),
                    color = colors.inkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HorizontalDivider(color = colors.line)
        }
    }
}
