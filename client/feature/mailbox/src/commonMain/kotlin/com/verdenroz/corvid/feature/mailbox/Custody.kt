package com.verdenroz.corvid.feature.mailbox

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.CorvidIcons
import com.verdenroz.corvid.core.model.CorvidKeywords
import com.verdenroz.corvid.core.model.EmailDetail
import com.verdenroz.corvid.core.model.Label
import com.verdenroz.corvid.core.model.Rewrite
import com.verdenroz.corvid.core.model.Seal
import com.verdenroz.corvid.core.ui.provenance.LabelChip
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.custody_by_labeler
import com.verdenroz.corvid.feature.mailbox.resources.custody_by_rule
import com.verdenroz.corvid.feature.mailbox.resources.custody_by_you
import com.verdenroz.corvid.feature.mailbox.resources.custody_claims_signed_by
import com.verdenroz.corvid.feature.mailbox.resources.custody_claims_unsigned
import com.verdenroz.corvid.feature.mailbox.resources.custody_dmarc_failed
import com.verdenroz.corvid.feature.mailbox.resources.custody_dmarc_passed
import com.verdenroz.corvid.feature.mailbox.resources.custody_guess
import com.verdenroz.corvid.feature.mailbox.resources.custody_images_proxied
import com.verdenroz.corvid.feature.mailbox.resources.custody_labelled
import com.verdenroz.corvid.feature.mailbox.resources.custody_links_disabled
import com.verdenroz.corvid.feature.mailbox.resources.custody_no_trackers
import com.verdenroz.corvid.feature.mailbox.resources.custody_not_authenticated
import com.verdenroz.corvid.feature.mailbox.resources.custody_nothing
import com.verdenroz.corvid.feature.mailbox.resources.custody_sender
import com.verdenroz.corvid.feature.mailbox.resources.custody_signed
import com.verdenroz.corvid.feature.mailbox.resources.custody_signed_by
import com.verdenroz.corvid.feature.mailbox.resources.custody_skipped
import com.verdenroz.corvid.feature.mailbox.resources.custody_skipped_detail
import com.verdenroz.corvid.feature.mailbox.resources.custody_trackers_removed
import com.verdenroz.corvid.feature.mailbox.resources.custody_unsigned
import com.verdenroz.corvid.feature.mailbox.resources.custody_unsigned_detail
import com.verdenroz.corvid.feature.mailbox.resources.custody_way_in
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private val CustodyShape = RoundedCornerShape(12.dp)

/**
 * Who sent it, what happened on the way in and who labelled it, side by side when [wide]. A
 * message the milter never saw has no middle cell: a missing header is not a clean message.
 */
@Composable
internal fun Custody(
    email: EmailDetail,
    wide: Boolean,
    teach: @Composable (Label.Guess) -> Unit,
) {
    val colors = CorvidTheme.colors
    val bad = email.provenance.suspect
    val edge =
        if (bad) {
            Brush.linearGradient(
                listOf(colors.danger, colors.danger),
            )
        } else {
            Brush.linearGradient(listOf(colors.sheenStart, colors.sheenEnd))
        }
    val cells =
        buildList<@Composable (Modifier) -> Unit> {
            add { SenderCell(email.provenance.seal, it.then(if (bad) Modifier.background(colors.dangerSoft) else Modifier)) }
            email.provenance.rewrite?.let { rewrite -> add { WayInCell(rewrite, bad, it) } }
            add { LabelledCell(email.keywords.mapNotNull(CorvidKeywords::labelOf), teach, it) }
        }
    val frame = Modifier.fillMaxWidth().clip(CustodyShape).border(1.dp, edge, CustodyShape)
    if (wide) {
        Row(frame.height(IntrinsicSize.Min)) {
            cells.forEachIndexed { index, cell ->
                if (index > 0) VerticalDivider(color = colors.line)
                cell(Modifier.weight(1f).fillMaxHeight())
            }
        }
    } else {
        Column(frame) {
            cells.forEachIndexed { index, cell ->
                if (index > 0) HorizontalDivider(color = colors.line)
                cell(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun SenderCell(
    seal: Seal,
    modifier: Modifier,
) {
    when (seal) {
        is Seal.Signed ->
            Cell(
                CorvidIcons.Seal,
                stringResource(Res.string.custody_sender),
                seal.signer?.let { stringResource(Res.string.custody_signed_by, it) } ?: stringResource(Res.string.custody_signed),
                stringResource(Res.string.custody_dmarc_passed),
                modifier,
            )
        is Seal.Failed -> {
            val claimed = seal.claimed
            val signedBy = seal.signedBy
            val detail =
                when {
                    claimed != null && signedBy != null -> stringResource(Res.string.custody_claims_signed_by, claimed, signedBy)
                    claimed != null -> stringResource(Res.string.custody_claims_unsigned, claimed)
                    else -> stringResource(Res.string.custody_dmarc_failed)
                }
            Cell(
                CorvidIcons.Alert,
                stringResource(Res.string.custody_sender),
                stringResource(Res.string.custody_not_authenticated),
                detail,
                modifier,
                CorvidTheme.colors.onDangerSoft,
            )
        }
        is Seal.Unsigned ->
            Cell(
                CorvidIcons.Seal,
                stringResource(Res.string.custody_sender),
                stringResource(Res.string.custody_unsigned),
                stringResource(Res.string.custody_unsigned_detail),
                modifier,
            )
    }
}

@Composable
private fun WayInCell(
    rewrite: Rewrite,
    bad: Boolean,
    modifier: Modifier,
) {
    val title = stringResource(Res.string.custody_way_in)
    val links = stringResource(Res.string.custody_links_disabled).takeIf { bad }
    when (rewrite) {
        is Rewrite.Clean -> Cell(CorvidIcons.Eye, title, stringResource(Res.string.custody_no_trackers), links, modifier)
        is Rewrite.Skipped ->
            Cell(
                CorvidIcons.Eye,
                title,
                stringResource(Res.string.custody_skipped),
                links ?: stringResource(Res.string.custody_skipped_detail),
                modifier,
            )
        is Rewrite.Stripped -> {
            val value =
                if (rewrite.stripped > 0) {
                    pluralStringResource(Res.plurals.custody_trackers_removed, rewrite.stripped, rewrite.stripped)
                } else {
                    stringResource(Res.string.custody_no_trackers)
                }
            val proxied = rewrite.proxied.takeIf { it > 0 }?.let { pluralStringResource(Res.plurals.custody_images_proxied, it, it) }
            Cell(CorvidIcons.Eye, title, value, links ?: proxied, modifier)
        }
    }
}

@Composable
private fun LabelledCell(
    labels: List<Label>,
    teach: @Composable (Label.Guess) -> Unit,
    modifier: Modifier,
) {
    val colors = CorvidTheme.colors
    val guess = labels.filterIsInstance<Label.Guess>().firstOrNull()
    val attribution =
        when {
            guess != null -> stringResource(Res.string.custody_guess)
            labels.isEmpty() -> null
            labels.all { it is Label.Rule } -> stringResource(Res.string.custody_by_rule)
            labels.all { it is Label.Labeler } -> stringResource(Res.string.custody_by_labeler)
            labels.all { it is Label.Yours } -> stringResource(Res.string.custody_by_you)
            else -> null
        }
    Column(modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Key(CorvidIcons.Tag, stringResource(Res.string.custody_labelled), colors.inkFaint)
        if (labels.isEmpty()) {
            Value(stringResource(Res.string.custody_nothing), colors.ink)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                labels.sortedBy { it.name }.forEach { LabelChip(it) }
            }
        }
        attribution?.let { Text(it, style = CorvidTheme.type.meta, color = colors.inkMuted) }
        guess?.let { teach(it) }
    }
}

@Composable
private fun Cell(
    icon: ImageVector,
    title: String,
    value: String,
    detail: String?,
    modifier: Modifier,
    tint: Color = CorvidTheme.colors.ink,
) {
    val colors = CorvidTheme.colors
    Column(modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Key(icon, title, if (tint == colors.ink) colors.inkFaint else tint)
        Value(value, tint)
        detail?.let { Text(it, style = CorvidTheme.type.meta, color = if (tint == colors.ink) colors.inkMuted else tint) }
    }
}

@Composable
private fun Key(
    icon: ImageVector,
    title: String,
    tint: Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Text(title.uppercase(), style = CorvidTheme.type.label, color = tint)
    }
}

@Composable
private fun Value(
    text: String,
    tint: Color,
) {
    Text(text, style = CorvidTheme.type.meta, fontWeight = FontWeight.SemiBold, color = tint)
}
