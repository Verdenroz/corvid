package com.verdenroz.corvid.app.shell

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.app.HomeTab
import com.verdenroz.corvid.app.Route
import com.verdenroz.corvid.appcommon.resources.Res
import com.verdenroz.corvid.appcommon.resources.settings_open
import com.verdenroz.corvid.appcommon.resources.tab_filters
import com.verdenroz.corvid.appcommon.resources.write
import com.verdenroz.corvid.core.designsystem.CorvidShape
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.CorvidIcons
import com.verdenroz.corvid.core.designsystem.component.drawIndicator
import org.jetbrains.compose.resources.stringResource

private val EXPANDED_WIDTH = 232.dp
private val RAIL_WIDTH = 66.dp
private val ICON_START = 14.dp
private val ICON_SIZE = 18.dp
private val ROW_HEIGHT = 38.dp

/** What the sidebar marks as current: a home tab, or one of the pages at its foot. */
internal sealed interface SidebarPlace {
    data class Tab(val tab: HomeTab) : SidebarPlace

    data object Filters : SidebarPlace

    data object Settings : SidebarPlace
}

/** A page opened from the sidebar's foot marks that page; anything else keeps the tab it was opened from. */
internal fun sidebarPlace(
    root: HomeTab,
    top: Route,
): SidebarPlace =
    when (top) {
        Route.Filters -> SidebarPlace.Filters
        Route.Settings, Route.Notices, Route.ApproveDevice -> SidebarPlace.Settings
        else -> SidebarPlace.Tab(root)
    }

/** What the wide sidebar and the narrow drawer both list, and where each choice goes. */
internal data class Places(
    val place: SidebarPlace,
    val inboxUnread: Int?,
    val onSelect: (HomeTab) -> Unit,
    val onFilters: () -> Unit,
    val onSettings: () -> Unit,
)

/** Folders first, then the views of who wrote, then Filters and Settings at the foot; [expanded] false draws icons alone. */
@Composable
internal fun Sidebar(
    places: Places,
    expanded: Boolean,
    onWrite: () -> Unit,
) {
    val colors = CorvidTheme.colors
    val width by animateDpAsState(if (expanded) EXPANDED_WIDTH else RAIL_WIDTH)
    Row(Modifier.fillMaxHeight().background(colors.surface)) {
        Column(Modifier.width(width).fillMaxHeight().padding(horizontal = 10.dp)) {
            WriteButton(expanded, onWrite, Modifier.padding(top = 14.dp, bottom = 14.dp))
            PlaceList(places, expanded, ROW_HEIGHT)
            Spacer(Modifier.height(12.dp))
        }
        VerticalDivider(color = colors.line)
    }
}

/** Every home tab in a scrolling column, then Filters and Settings pinned at the foot; the sidebar and the narrow drawer share it. */
@Composable
internal fun ColumnScope.PlaceList(
    places: Places,
    expanded: Boolean,
    rowHeight: Dp,
) {
    val place = places.place
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        HomeTab.entries.forEach { tab ->
            if (tab == HomeTab.People) SectionBreak()
            Item(
                stringResource(tab.title()),
                tab.icon(),
                selected = place == SidebarPlace.Tab(tab),
                expanded = expanded,
                count = places.inboxUnread.takeIf { tab == HomeTab.Inbox },
                rowHeight = rowHeight,
            ) { places.onSelect(tab) }
        }
    }
    SectionBreak()
    Item(
        stringResource(Res.string.tab_filters),
        CorvidIcons.Filter,
        place == SidebarPlace.Filters,
        expanded,
        null,
        rowHeight,
        places.onFilters,
    )
    Item(
        stringResource(Res.string.settings_open),
        CorvidIcons.Settings,
        place == SidebarPlace.Settings,
        expanded,
        null,
        rowHeight,
        places.onSettings,
    )
}

@Composable
private fun SectionBreak() {
    HorizontalDivider(
        color = CorvidTheme.colors.line,
        modifier = Modifier.padding(start = ICON_START, end = 4.dp, top = 10.dp, bottom = 10.dp),
    )
}

@Composable
private fun WriteButton(
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val colors = CorvidTheme.colors
    val label = stringResource(Res.string.write)
    Surface(
        onClick = onClick,
        shape = CorvidShape.medium,
        color = colors.ink,
        contentColor = colors.surface,
        modifier = modifier.semantics { contentDescription = label },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.height(44.dp).padding(start = ICON_START, end = if (expanded) 20.dp else ICON_START),
        ) {
            Icon(CorvidIcons.Pencil, contentDescription = null, modifier = Modifier.size(ICON_SIZE))
            if (expanded) Text(label, style = CorvidTheme.type.action, maxLines = 1)
        }
    }
}

@Composable
private fun Item(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    expanded: Boolean,
    count: Int?,
    rowHeight: Dp,
    onClick: () -> Unit,
) {
    val colors = CorvidTheme.colors
    val ink = if (selected) colors.ink else colors.inkMuted
    val shown = count?.takeIf { it > 0 }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .height(rowHeight)
                .clip(CorvidShape.small)
                .clickable(role = Role.Tab, onClick = onClick)
                .semantics {
                    this.selected = selected
                    if (!expanded) contentDescription = title
                }.drawBehind { if (selected) drawIndicator(colors.accent, centerX = (ICON_START + ICON_SIZE / 2).toPx()) }
                .padding(start = ICON_START, end = 10.dp),
    ) {
        Box {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(ICON_SIZE))
            if (!expanded && shown != null) {
                Box(Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-2).dp).size(6.dp).clip(CorvidShape.pill).background(colors.ink))
            }
        }
        if (expanded) {
            Text(
                title,
                style = CorvidTheme.type.name,
                fontWeight = if (selected || shown != null) FontWeight.Bold else FontWeight.Normal,
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            shown?.let { Text(it.toString(), style = CorvidTheme.type.metaNumeric, color = ink) }
        }
    }
}
