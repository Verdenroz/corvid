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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.designsystem.CorvidShape
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.CorvidIcons
import com.verdenroz.corvid.core.model.EmailDetail
import com.verdenroz.corvid.core.model.Label
import com.verdenroz.corvid.core.model.Seal
import com.verdenroz.corvid.core.model.labels
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.core.ui.provenance.LabelChip
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.custody_labelled
import com.verdenroz.corvid.feature.mailbox.resources.custody_sender
import com.verdenroz.corvid.feature.mailbox.resources.custody_way_in
import org.jetbrains.compose.resources.stringResource

/** Who sent it, what happened on the way in and who labelled it, side by side when [wide]. */
@Composable
internal fun Custody(
    email: EmailDetail,
    wide: Boolean,
    teach: @Composable (Label.Guess) -> Unit,
) {
    val colors = CorvidTheme.colors
    val edge = if (email.provenance.suspect) SolidColor(colors.danger) else CorvidTheme.sheen
    val cells = custodyCells(email.provenance, email.labels)
    val frame = Modifier.fillMaxWidth().clip(CorvidShape.medium).border(1.dp, edge, CorvidShape.medium)
    if (wide) {
        Row(frame.height(IntrinsicSize.Min)) {
            cells.forEachIndexed { index, cell ->
                if (index > 0) VerticalDivider(color = colors.line)
                CustodyCellView(cell, teach, Modifier.weight(1f).fillMaxHeight())
            }
        }
    } else {
        Column(frame) {
            cells.forEachIndexed { index, cell ->
                if (index > 0) HorizontalDivider(color = colors.line)
                CustodyCellView(cell, teach, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun CustodyCellView(
    cell: CustodyCell,
    teach: @Composable (Label.Guess) -> Unit,
    modifier: Modifier,
) {
    when (cell) {
        is CustodyCell.Sender ->
            Cell(
                if (cell.danger) CorvidIcons.Alert else CorvidIcons.Seal,
                stringResource(Res.string.custody_sender),
                cell.value.asString(),
                cell.detail.asString(),
                if (cell.danger) modifier.background(CorvidTheme.colors.dangerSoft) else modifier,
                cell.danger,
            )
        is CustodyCell.WayIn ->
            Cell(CorvidIcons.Eye, stringResource(Res.string.custody_way_in), cell.value.asString(), cell.detail?.asString(), modifier)
        is CustodyCell.Labelled -> LabelledCell(cell, teach, modifier)
    }
}

@Composable
private fun LabelledCell(
    cell: CustodyCell.Labelled,
    teach: @Composable (Label.Guess) -> Unit,
    modifier: Modifier,
) {
    val colors = CorvidTheme.colors
    Column(modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Key(CorvidIcons.Tag, stringResource(Res.string.custody_labelled), colors.inkFaint)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            cell.labels.forEach { LabelChip(it) }
        }
        cell.attribution?.let { Text(it.asString(), style = CorvidTheme.type.meta, color = colors.inkMuted) }
        cell.guess?.let { teach(it) }
    }
}

@Composable
private fun Cell(
    icon: ImageVector,
    title: String,
    value: String,
    detail: String?,
    modifier: Modifier,
    danger: Boolean = false,
) {
    val colors = CorvidTheme.colors
    val ink = if (danger) colors.onDangerSoft else colors.ink
    Column(modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Key(icon, title, if (danger) ink else colors.inkFaint)
        Value(value, ink)
        detail?.let { Text(it, style = CorvidTheme.type.meta, color = if (danger) ink else colors.inkMuted) }
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
