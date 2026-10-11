package com.verdenroz.corvid.app.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.verdenroz.corvid.app.HomeTab
import com.verdenroz.corvid.core.designsystem.CorvidShape
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import kotlinx.coroutines.launch

private val DRAWER_WIDTH = 296.dp
private val DRAWER_ROW = 48.dp

/**
 * Narrow only: the sidebar's places in a drawer from the start edge. A swipe right opens it while
 * [enabled]; Back, the scrim and any choice close it.
 */
@Composable
internal fun NavDrawer(
    state: DrawerState,
    enabled: Boolean,
    place: SidebarPlace,
    inboxUnread: Int?,
    onSelect: (HomeTab) -> Unit,
    onFilters: () -> Unit,
    onSettings: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = CorvidTheme.colors
    val scope = rememberCoroutineScope()
    val opening = state.targetValue == DrawerValue.Open

    fun choose(action: () -> Unit) {
        action()
        scope.launch { state.close() }
    }
    // Composed only while open, so it registers after every page's handler and takes Back first.
    if (opening) {
        NavigationBackHandler(
            state = rememberNavigationEventState(NavigationEventInfo.None),
            isBackEnabled = true,
            onBackCompleted = { scope.launch { state.close() } },
        )
    }
    ModalNavigationDrawer(
        drawerState = state,
        gesturesEnabled = enabled || opening || state.isOpen,
        scrimColor = colors.scrim,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(DRAWER_WIDTH),
                drawerShape = CorvidShape.medium.copy(topStart = CornerSize(0), bottomStart = CornerSize(0)),
                drawerContainerColor = colors.rail,
                drawerContentColor = colors.ink,
            ) {
                Box(Modifier.padding(start = 24.dp, top = 20.dp, bottom = 14.dp)) { Wordmark() }
                Column(Modifier.weight(1f).padding(start = 10.dp, end = 10.dp, bottom = 12.dp)) {
                    Places(
                        place,
                        inboxUnread,
                        expanded = true,
                        rowHeight = DRAWER_ROW,
                        onSelect = { choose { onSelect(it) } },
                        onFilters = { choose(onFilters) },
                        onSettings = { choose(onSettings) },
                    )
                }
            }
        },
        content = content,
    )
}
