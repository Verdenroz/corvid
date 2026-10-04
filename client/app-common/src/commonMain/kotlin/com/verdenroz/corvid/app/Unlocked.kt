package com.verdenroz.corvid.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.verdenroz.corvid.app.shell.HomeContent
import com.verdenroz.corvid.app.shell.KeyState
import com.verdenroz.corvid.app.shell.NarrowTopBar
import com.verdenroz.corvid.app.shell.TabsBar
import com.verdenroz.corvid.app.shell.WideTopBar
import com.verdenroz.corvid.app.shell.rememberRemaining
import com.verdenroz.corvid.core.data.ComposeRepository
import com.verdenroz.corvid.core.data.FiltersRepository
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.data.SenderRepository
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.model.ComposeIntent
import com.verdenroz.corvid.feature.compose.ComposeScreen
import com.verdenroz.corvid.feature.filters.FiltersScreen
import com.verdenroz.corvid.feature.mailbox.ReaderScreen
import com.verdenroz.corvid.feature.mailbox.SenderCard
import com.verdenroz.corvid.feature.mailbox.SenderCardActions
import com.verdenroz.corvid.feature.mailbox.WIDE_LEDGER
import org.koin.compose.koinInject
import org.koin.core.scope.Scope

/** Every pointer event here resets the idle timer. */
@Composable
internal fun UnlockedContent(
    host: String,
    onLock: () -> Unit,
    idleTimeout: IdleTimeoutController = koinInject(),
    scopeHolder: UnlockedScopeHolder = koinInject(),
) {
    val scope = scopeHolder.current()
    val repository = remember(scope) { scope.get<MailboxRepository>() }
    val shell = remember(scope) { ShellViewModel(repository) }

    DisposableEffect(Unit) {
        idleTimeout.recordActivity()
        onDispose { idleTimeout.stop() }
    }

    val backStack = rememberSaveable(saver = RouteStackSaver) { mutableStateListOf<Route>(Route.Home(HomeTab.Inbox)) }
    val route = backStack.last()
    LaunchedEffect(route) { if (route is Route.Home) shell.refresh() }
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = shellHandlesBack(route, backStack.size),
        onBackCompleted = { backStack.back() },
    )

    val shellState by shell.state.collectAsState()
    val locksAt by idleTimeout.locksAt.collectAsState()
    val key = KeyState(host, rememberRemaining(locksAt), idleTimeout.timeout, onLock)
    BoxWithConstraints(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent()
                    idleTimeout.recordActivity()
                }
            }
        },
    ) {
        val wide = maxWidth >= WIDE_LEDGER
        Column(Modifier.fillMaxSize()) {
            if (wide) WideTopBar(key, shellState.identity)
            // Wide, a card is a sheet and compose a panel over the screen they were opened from.
            val floats = wide && (route is Route.Sender || route is Route.Compose) && backStack.size > 1
            val page = if (floats) backStack[backStack.lastIndex - 1] else route
            Box(Modifier.weight(1f)) {
                Page(page, wide, key, shellState, scope, repository, backStack)
                when {
                    !floats -> Unit
                    route is Route.Sender -> {
                        Box(
                            Modifier.fillMaxSize().background(
                                CorvidTheme.colors.scrim,
                            ).clickable(interactionSource = null, indication = null) { backStack.back() },
                        )
                        Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(540.dp).shadow(16.dp)) {
                            Card(route, wide = true, scope, repository, backStack)
                        }
                    }
                    route is Route.Compose -> {
                        val shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
                        Box(
                            Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 24.dp)
                                .width(540.dp)
                                .height(470.dp)
                                .shadow(16.dp, shape)
                                .border(1.dp, CorvidTheme.colors.line, shape)
                                .clip(shape),
                        ) {
                            Page(route, wide, key, shellState, scope, repository, backStack)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Page(
    route: Route,
    wide: Boolean,
    key: KeyState,
    shellState: ShellState,
    scope: Scope,
    repository: MailboxRepository,
    backStack: SnapshotStateList<Route>,
) {
    val openSender: (String) -> Unit = { backStack += Route.Sender(it) }
    when (route) {
        is Route.Home ->
            Column {
                if (!wide) NarrowTopBar(key)
                TabsBar(
                    current = route.tab,
                    inboxUnread = shellState.inboxUnread,
                    wide = wide,
                    onSelect = { backStack[0] = Route.Home(it) },
                    onFilters = { backStack += Route.Filters },
                )
                HomeContent(
                    tab = route.tab,
                    repository = repository,
                    onOpenEmail = { backStack += Route.Reader(it) },
                    onOpenSender = openSender,
                    onWrite = { backStack += Route.Compose(ComposeIntent.New) },
                )
            }
        is Route.Reader ->
            ReaderScreen(
                repository,
                route.emailId,
                onBack = { backStack.back() },
                onCompose = { backStack += Route.Compose(it) },
                onOpenSender = openSender,
            )
        is Route.Compose -> ComposeScreen(remember(scope) { scope.get<ComposeRepository>() }, route.intent, onClose = { backStack.back() })
        is Route.Filters -> FiltersScreen(remember(scope) { scope.get<FiltersRepository>() }, onBack = { backStack.back() })
        is Route.Sender -> Card(route, wide, scope, repository, backStack)
    }
}

@Composable
private fun Card(
    route: Route.Sender,
    wide: Boolean,
    scope: Scope,
    repository: MailboxRepository,
    backStack: SnapshotStateList<Route>,
) {
    SenderCard(
        mailbox = repository,
        senders = remember(scope) { scope.get<SenderRepository>() },
        address = route.address,
        wide = wide,
        actions =
            SenderCardActions(
                close = { backStack.back() },
                openEmail = { backStack += Route.Reader(it) },
                compose = { backStack += Route.Compose(it) },
                makeFilter = { backStack += Route.Filters },
            ),
    )
}

/** Pops a pushed screen; from a home tab other than Inbox, returns to Inbox. */
private fun SnapshotStateList<Route>.back() {
    if (size > 1) removeAt(lastIndex) else this[0] = Route.Home(HomeTab.Inbox)
}

/**
 * Whether back belongs to the shell. Compose guards its own back so unsent text is not dropped,
 * and two enabled handlers do not resolve in the same order on every platform, so the shell steps aside.
 */
internal fun shellHandlesBack(
    route: Route,
    depth: Int,
): Boolean = route !is Route.Compose && (depth > 1 || route != Route.Home(HomeTab.Inbox))
