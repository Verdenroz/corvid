package com.verdenroz.corvid.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.app.HomeTab
import com.verdenroz.corvid.appcommon.resources.Res
import com.verdenroz.corvid.appcommon.resources.tab_drafts
import com.verdenroz.corvid.appcommon.resources.tab_filters
import com.verdenroz.corvid.appcommon.resources.tab_inbox
import com.verdenroz.corvid.appcommon.resources.tab_labels
import com.verdenroz.corvid.appcommon.resources.tab_people
import com.verdenroz.corvid.appcommon.resources.tab_sent
import com.verdenroz.corvid.appcommon.resources.tab_sources
import com.verdenroz.corvid.appcommon.resources.tab_starred
import com.verdenroz.corvid.appcommon.resources.tab_trash
import com.verdenroz.corvid.core.designsystem.CorvidShape
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.CorvidIcons
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Wide, Labels and Filters sit apart at the far end; narrow, the whole row scrolls. */
@Composable
internal fun TabsBar(
    current: HomeTab,
    inboxUnread: Int?,
    wide: Boolean,
    onSelect: (HomeTab) -> Unit,
    onFilters: () -> Unit,
) {
    Column(Modifier.background(CorvidTheme.colors.surface)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(if (wide) Modifier else Modifier.horizontalScroll(rememberScrollState()))
                    .padding(start = if (wide) 24.dp else 12.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
        ) {
            HomeTab.entries.filter { it != HomeTab.Labels }.forEach { tab ->
                Tab(
                    tab.title(),
                    tab.icon(),
                    selected = tab == current,
                    count = inboxUnread.takeIf { tab == HomeTab.Inbox },
                ) { onSelect(tab) }
            }
            Spacer(if (wide) Modifier.weight(1f) else Modifier.width(12.dp))
            Tab(HomeTab.Labels.title(), CorvidIcons.Tag, selected = current == HomeTab.Labels, count = null) { onSelect(HomeTab.Labels) }
            Tab(Res.string.tab_filters, CorvidIcons.Filter, selected = false, count = null, onClick = onFilters)
        }
        HorizontalDivider(color = CorvidTheme.colors.line)
    }
}

@Composable
private fun Tab(
    title: StringResource,
    icon: ImageVector,
    selected: Boolean,
    count: Int?,
    onClick: () -> Unit,
) {
    val colors = CorvidTheme.colors
    val ink = if (selected) colors.ink else colors.inkMuted
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier =
            Modifier
                .height(36.dp)
                .clip(CorvidShape.small)
                .clickable(role = Role.Tab, onClick = onClick)
                .semantics { this.selected = selected }
                .drawBehind { if (selected) drawIndicator(colors.accent) }
                .padding(horizontal = 12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
        Text(
            stringResource(title),
            style = CorvidTheme.type.name,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = ink,
        )
        count?.takeIf { it > 0 }?.let {
            Text(it.toString(), style = CorvidTheme.type.metaNumeric, color = ink)
        }
    }
}

private fun DrawScope.drawIndicator(color: Color) {
    val width = 20.dp.toPx()
    drawRoundRect(
        color,
        Offset((size.width - width) / 2, size.height - 2.dp.toPx()),
        Size(width, 2.dp.toPx()),
        CornerRadius(1.dp.toPx()),
    )
}

private fun HomeTab.title(): StringResource =
    when (this) {
        HomeTab.Inbox -> Res.string.tab_inbox
        HomeTab.People -> Res.string.tab_people
        HomeTab.Sources -> Res.string.tab_sources
        HomeTab.Starred -> Res.string.tab_starred
        HomeTab.Sent -> Res.string.tab_sent
        HomeTab.Drafts -> Res.string.tab_drafts
        HomeTab.Trash -> Res.string.tab_trash
        HomeTab.Labels -> Res.string.tab_labels
    }

private fun HomeTab.icon(): ImageVector =
    when (this) {
        HomeTab.Inbox -> CorvidIcons.Inbox
        HomeTab.People -> CorvidIcons.Person
        HomeTab.Sources -> CorvidIcons.Box
        HomeTab.Starred -> CorvidIcons.Star
        HomeTab.Sent -> CorvidIcons.Send
        HomeTab.Drafts -> CorvidIcons.Draft
        HomeTab.Trash -> CorvidIcons.Trash
        HomeTab.Labels -> CorvidIcons.Tag
    }
