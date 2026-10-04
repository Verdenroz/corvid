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
import com.verdenroz.corvid.core.designsystem.CorvidShape
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.CorvidIcons
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Tabs, not a drawer: Labels and Filters sit apart at the far end when wide; narrow, the row scrolls. */
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
    icon: ImageVector?,
    selected: Boolean,
    count: Int?,
    onClick: () -> Unit,
) {
    val colors = CorvidTheme.colors
    val ink = if (selected) colors.onAccentSoft else colors.inkMuted
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier =
            Modifier
                .height(32.dp)
                .clip(CorvidShape.pill)
                .background(if (selected) colors.accentSoft else colors.surface)
                .clickable(role = Role.Tab, onClick = onClick)
                .semantics { this.selected = selected }
                .padding(horizontal = 12.dp),
    ) {
        icon?.let { Icon(it, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp)) }
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

private fun HomeTab.title(): StringResource =
    when (this) {
        HomeTab.Inbox -> Res.string.tab_inbox
        HomeTab.People -> Res.string.tab_people
        HomeTab.Sources -> Res.string.tab_sources
        HomeTab.Starred -> Res.string.tab_starred
        HomeTab.Sent -> Res.string.tab_sent
        HomeTab.Drafts -> Res.string.tab_drafts
        HomeTab.Labels -> Res.string.tab_labels
    }

private fun HomeTab.icon(): ImageVector? =
    when (this) {
        HomeTab.Inbox -> CorvidIcons.Inbox
        HomeTab.People -> CorvidIcons.Person
        HomeTab.Sources -> CorvidIcons.Box
        else -> null
    }
