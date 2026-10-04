package com.verdenroz.corvid.feature.mailbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.data.SenderRepository
import com.verdenroz.corvid.core.data.clockTime
import com.verdenroz.corvid.core.data.dayHeading
import com.verdenroz.corvid.core.data.mailtoIntent
import com.verdenroz.corvid.core.data.monthYear
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.CorvidIcons
import com.verdenroz.corvid.core.designsystem.component.PillButton
import com.verdenroz.corvid.core.designsystem.component.PrimaryButton
import com.verdenroz.corvid.core.model.ComposeIntent
import com.verdenroz.corvid.core.model.CorvidKeywords
import com.verdenroz.corvid.core.model.EmailSummary
import com.verdenroz.corvid.core.model.Seal
import com.verdenroz.corvid.core.model.SenderKind
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.core.ui.provenance.LabelChip
import com.verdenroz.corvid.core.ui.provenance.SenderAvatar
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.card_authenticity
import com.verdenroz.corvid.feature.mailbox.resources.card_between
import com.verdenroz.corvid.feature.mailbox.resources.card_between_you
import com.verdenroz.corvid.feature.mailbox.resources.card_close
import com.verdenroz.corvid.feature.mailbox.resources.card_filed_as
import com.verdenroz.corvid.feature.mailbox.resources.card_make_filter
import com.verdenroz.corvid.feature.mailbox.resources.card_messages_since
import com.verdenroz.corvid.feature.mailbox.resources.card_not_labelled
import com.verdenroz.corvid.feature.mailbox.resources.card_recent
import com.verdenroz.corvid.feature.mailbox.resources.card_source_kind
import com.verdenroz.corvid.feature.mailbox.resources.card_trackers
import com.verdenroz.corvid.feature.mailbox.resources.card_unsubscribe
import com.verdenroz.corvid.feature.mailbox.resources.card_write
import com.verdenroz.corvid.feature.mailbox.resources.card_writes
import com.verdenroz.corvid.feature.mailbox.resources.card_writing_since
import com.verdenroz.corvid.feature.mailbox.resources.mailbox_no_subject
import com.verdenroz.corvid.feature.mailbox.resources.mailbox_separator
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

/** What a card can ask the shell to do. */
class SenderCardActions(
    val close: () -> Unit,
    val openEmail: (String) -> Unit,
    val compose: (ComposeIntent) -> Unit,
    val makeFilter: () -> Unit,
)

@Composable
fun SenderCard(
    mailbox: MailboxRepository,
    senders: SenderRepository,
    address: String,
    wide: Boolean,
    actions: SenderCardActions,
) {
    val model = remember(address) { SenderCardModel(mailbox, senders, address) }
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
                    ).padding(horizontal = if (wide) 26.dp else 18.dp, vertical = 10.dp),
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
                    val y = size.height - 1.dp.toPx()
                    drawLine(
                        Brush.linearGradient(listOf(colors.sheenStart, colors.sheenEnd)),
                        Offset(0f, y),
                        Offset(size.width, y),
                        2.dp.toPx(),
                    )
                }.padding(start = if (wide) 26.dp else 18.dp, end = 18.dp, bottom = 18.dp),
    ) {
        SenderAvatar(name, sender is SenderKind.Source, seal, size = if (wide) 60.dp else 52.dp)
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
                seal?.let { senderAddress(address, it).asString() } ?: address,
                style = CorvidTheme.type.meta,
                color = if (seal is Seal.Failed) colors.danger else colors.inkMuted,
            )
        }
    }
}

@Composable
private fun Facts(
    state: SenderCardState,
    latest: EmailSummary?,
    isSource: Boolean,
    wide: Boolean,
) {
    val zone = remember { TimeZone.currentSystemDefault() }
    val window = state.window
    val counts = state.counts
    val signer = (latest?.provenance?.seal as? Seal.Signed)?.signer
    val authenticity = window?.let { authenticityFact(it, counts?.received, if (isSource) signer else null)?.asString() }
    val trackers = window?.let { trackersFact(it, counts?.received)?.asString() }
    val since = counts?.firstSeen?.let { monthYear(it, zone).asString() }
    val facts =
        if (isSource) {
            listOf(
                Fact(Res.string.card_authenticity, authenticity, window != null && window.signed < window.size),
                Fact(
                    Res.string.card_writes,
                    counts?.let {
                            c ->
                        since?.let { pluralStringResource(Res.plurals.card_messages_since, c.received, c.received, it) }
                    },
                ),
                Fact(Res.string.card_trackers, trackers),
                Fact(Res.string.card_filed_as, null, labels = latest),
            )
        } else {
            listOf(
                Fact(Res.string.card_authenticity, authenticity, window != null && window.signed < window.size),
                Fact(Res.string.card_writing_since, since),
                Fact(Res.string.card_between_you, counts?.let { stringResource(Res.string.card_between, it.received, it.sent) }),
                Fact(Res.string.card_trackers, trackers),
            )
        }
    Column {
        facts.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                pair.forEachIndexed { index, fact ->
                    if (index > 0) VerticalDivider(color = CorvidTheme.colors.line)
                    FactCell(fact, wide, Modifier.weight(1f).fillMaxHeight())
                }
            }
            HorizontalDivider(color = CorvidTheme.colors.line)
        }
    }
}

private class Fact(
    val key: StringResource,
    val value: String?,
    val warn: Boolean = false,
    val labels: EmailSummary? = null,
)

@Composable
private fun FactCell(
    fact: Fact,
    wide: Boolean,
    modifier: Modifier,
) {
    val colors = CorvidTheme.colors
    Column(modifier.padding(horizontal = if (wide) 26.dp else 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(fact.key).uppercase(), style = CorvidTheme.type.label, color = colors.inkFaint)
        val labels = fact.labels?.keywords?.mapNotNull(CorvidKeywords::labelOf)
        when {
            labels != null && labels.isNotEmpty() ->
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) { labels.forEach { LabelChip(it) } }
            labels != null -> Text(stringResource(Res.string.card_not_labelled), style = CorvidTheme.type.metaStrong)
            else -> Text(fact.value.orEmpty(), style = CorvidTheme.type.metaStrong, color = if (fact.warn) colors.danger else colors.ink)
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
    val unsubscribe = latest?.provenance?.unsubscribe.orEmpty()
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = if (wide) 26.dp else 18.dp, vertical = 14.dp),
    ) {
        if (isSource) {
            if (unsubscribe.isNotEmpty()) {
                PillButton(stringResource(Res.string.card_unsubscribe), CorvidIcons.Unsubscribe, {
                    val mail = unsubscribe.firstNotNullOfOrNull(::mailtoIntent)
                    if (mail != null) actions.compose(mail) else uri.openUri(unsubscribe.first())
                })
            }
            PillButton(stringResource(Res.string.card_make_filter), CorvidIcons.Filter, actions.makeFilter)
        } else if (!writeIsPrimary(latest?.provenance?.seal)) {
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
    onOpen: (String) -> Unit,
    modifier: Modifier,
) {
    val colors = CorvidTheme.colors
    val zone = remember { TimeZone.currentSystemDefault() }
    val today = Clock.System.now().toLocalDateTime(zone).date
    val separator = stringResource(Res.string.mailbox_separator)
    LazyColumn(modifier.fillMaxWidth().padding(horizontal = if (wide) 26.dp else 18.dp)) {
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
                Modifier.fillMaxWidth().clickable { onOpen(email.id) }.padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    whenArrived(email, zone, today),
                    style = CorvidTheme.type.meta.copy(fontFeatureSettings = "tnum"),
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

/** The time for today's mail, otherwise the same day label the ledger's header would give it. */
@Composable
private fun whenArrived(
    email: EmailSummary,
    zone: TimeZone,
    today: LocalDate,
): String {
    val at = email.receivedAt.toLocalDateTime(zone)
    return if (at.date == today) clockTime(at.hour, at.minute).asString() else dayHeading(at.date, today).title.asString()
}
