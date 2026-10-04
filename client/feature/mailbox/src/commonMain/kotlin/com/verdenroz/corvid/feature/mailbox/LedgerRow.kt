package com.verdenroz.corvid.feature.mailbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.data.clockTime
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.model.CorvidKeywords
import com.verdenroz.corvid.core.model.EmailSummary
import com.verdenroz.corvid.core.model.Rewrite
import com.verdenroz.corvid.core.model.Seal
import com.verdenroz.corvid.core.model.SenderKind
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.core.ui.provenance.LabelChip
import com.verdenroz.corvid.core.ui.provenance.SenderAvatar
import com.verdenroz.corvid.core.ui.provenance.TrackerCount
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.mailbox_no_subject
import com.verdenroz.corvid.feature.mailbox.resources.mailbox_separator
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LedgerRow(
    email: EmailSummary,
    zone: TimeZone,
    wide: Boolean,
    onClick: () -> Unit,
) {
    val colors = CorvidTheme.colors
    val suspect = email.provenance.seal is Seal.Failed
    Column(Modifier.fillMaxWidth().background(if (suspect) colors.dangerTint else colors.surface).clickable(onClick = onClick)) {
        if (wide) WideRow(email, zone) else NarrowRow(email, zone)
        HorizontalDivider(color = colors.line)
    }
}

@Composable
private fun WideRow(
    email: EmailSummary,
    zone: TimeZone,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.padding(start = 16.dp, end = 24.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Time(email, zone, Modifier.width(58.dp))
        Avatar(email, 40)
        SenderName(email, Modifier.width(200.dp))
        Summary(email, Modifier.weight(1f))
        (email.provenance.rewrite as? Rewrite.Stripped)?.takeIf { it.stripped > 0 }?.let { TrackerCount(it.stripped) }
    }
}

@Composable
private fun NarrowRow(
    email: EmailSummary,
    zone: TimeZone,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp)) {
        Avatar(email, 40)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                SenderName(email, Modifier.weight(1f))
                Time(email, zone, Modifier.padding(start = 8.dp, top = 2.dp))
            }
            Summary(email, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Time(
    email: EmailSummary,
    zone: TimeZone,
    modifier: Modifier,
) {
    val at = email.receivedAt.toLocalDateTime(zone)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        modifier = modifier,
    ) {
        if (email.isUnread) Box(Modifier.size(7.dp).background(CorvidTheme.colors.accent, CircleShape))
        Text(
            clockTime(at.hour, at.minute).asString(),
            style = CorvidTheme.type.meta.copy(fontFeatureSettings = "tnum"),
            color = CorvidTheme.colors.inkFaint,
        )
    }
}

@Composable
private fun Avatar(
    email: EmailSummary,
    size: Int,
) {
    SenderAvatar(
        name = email.from.displayName(),
        isSource = email.provenance.sender is SenderKind.Source,
        seal = email.provenance.seal,
        size = size.dp,
    )
}

@Composable
private fun SenderName(
    email: EmailSummary,
    modifier: Modifier,
) {
    Column(modifier) {
        Text(
            email.from.displayName(),
            style = CorvidTheme.type.name,
            fontWeight = if (email.isUnread) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        email.provenance.sender.sourceLabel()?.let {
            Text(it, style = CorvidTheme.type.meta, color = CorvidTheme.colors.inkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Summary(
    email: EmailSummary,
    modifier: Modifier,
) {
    val colors = CorvidTheme.colors
    val subjectColor = if (email.provenance.seal is Seal.Failed) colors.danger else colors.ink
    val subject = email.subject.ifEmpty { stringResource(Res.string.mailbox_no_subject) }
    val separator = stringResource(Res.string.mailbox_separator)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
        email.keywords.mapNotNull(CorvidKeywords::labelOf).sortedBy { it.name }.forEach { LabelChip(it) }
        Text(
            buildAnnotatedString {
                withStyle(
                    SpanStyle(color = subjectColor, fontWeight = if (email.isUnread) FontWeight.Bold else FontWeight.Medium),
                ) { append(subject) }
                if (email.preview.isNotBlank()) append(" $separator ${email.preview}")
            },
            style = CorvidTheme.type.name.copy(fontWeight = FontWeight.Normal),
            color = colors.inkMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal val EmailSummary.isUnread: Boolean get() = "\$seen" !in keywords
