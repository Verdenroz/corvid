package com.verdenroz.corvid.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.data.fileBadge
import com.verdenroz.corvid.core.designsystem.CorvidShape
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.ActionIcon
import com.verdenroz.corvid.core.designsystem.component.CorvidIcons
import com.verdenroz.corvid.core.ui.resources.Res
import com.verdenroz.corvid.core.ui.resources.attachment_remove
import com.verdenroz.corvid.core.ui.resources.attachment_unnamed
import org.jetbrains.compose.resources.stringResource

/**
 * One file as a hairline tile: its kind, its name and a [detail] line under it, which is the size
 * or what is happening to the file. [onRemove] adds a close mark, for a file not yet sent.
 */
@Composable
fun AttachmentTile(
    name: String?,
    type: String,
    detail: String,
    modifier: Modifier = Modifier,
    detailColor: Color = CorvidTheme.colors.inkMuted,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    onRemove: (() -> Unit)? = null,
) {
    val colors = CorvidTheme.colors
    val shown = name?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.attachment_unnamed)
    val clickable = onClick?.let { Modifier.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = it) } ?: Modifier
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier =
            modifier
                .widthIn(max = 300.dp)
                .heightIn(min = 52.dp)
                .clip(CorvidShape.medium)
                .border(1.dp, colors.line, CorvidShape.medium)
                .then(clickable)
                .padding(start = 8.dp, end = if (onRemove == null) 14.dp else 0.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Badge(fileBadge(name, type))
        Column(Modifier.weight(1f, fill = false)) {
            Text(shown, style = CorvidTheme.type.name, color = colors.ink, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Text(detail, style = CorvidTheme.type.meta, color = detailColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        onRemove?.let { ActionIcon(stringResource(Res.string.attachment_remove, shown), CorvidIcons.Close, it, iconSize = 16.dp) }
    }
}

@Composable
private fun Badge(tag: String?) {
    val colors = CorvidTheme.colors
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(36.dp).background(colors.chip, CorvidShape.small)) {
        if (tag == null) {
            Icon(CorvidIcons.Paperclip, contentDescription = null, tint = colors.inkMuted, modifier = Modifier.size(18.dp))
        } else {
            Text(tag, style = CorvidTheme.type.chip, color = colors.inkMuted, maxLines = 1)
        }
    }
}
