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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.app.HomeTab
import com.verdenroz.corvid.appcommon.resources.Res
import com.verdenroz.corvid.appcommon.resources.tab_filters
import com.verdenroz.corvid.core.designsystem.CorvidShape
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.CorvidIcons
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Narrow only: every home tab in one scrolling row, Filters at its end; wide has the sidebar instead. */
@Composable
internal fun TabsBar(
    current: HomeTab,
    inboxUnread: Int?,
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
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
        ) {
            HomeTab.entries.forEach { tab ->
                if (tab == HomeTab.People) Spacer(Modifier.width(12.dp))
                Tab(
                    tab.title(),
                    tab.icon(),
                    selected = tab == current,
                    count = inboxUnread.takeIf { tab == HomeTab.Inbox },
                ) { onSelect(tab) }
            }
            Spacer(Modifier.width(12.dp))
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
