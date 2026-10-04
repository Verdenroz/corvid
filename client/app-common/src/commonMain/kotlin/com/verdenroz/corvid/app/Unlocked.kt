package com.verdenroz.corvid.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
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
import com.verdenroz.corvid.core.designsystem.CorvidShape
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.model.ComposeIntent
import com.verdenroz.corvid.core.ui.LocalBackGuard
import com.verdenroz.corvid.core.ui.LocalWideLayout
import com.verdenroz.corvid.core.ui.WIDE_LAYOUT
import com.verdenroz.corvid.feature.compose.ComposeScreen
import com.verdenroz.corvid.feature.filters.FiltersScreen
import com.verdenroz.corvid.feature.mailbox.ReaderScreen
import com.verdenroz.corvid.feature.mailbox.SenderCard
import com.verdenroz.corvid.feature.mailbox.SenderCardActions
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
    val ledgers = remember(repository) { Ledgers(repository) }
    DisposableEffect(ledgers) { onDispose { ledgers.clear() } }

    DisposableEffect(Unit) {
        idleTimeout.recordActivity()
        onDispose { idleTimeout.stop() }
    }

    val backStack = rememberSaveable(saver = RouteStackSaver) { mutableStateListOf<Route>(Route.Home(HomeTab.Inbox)) }
    val saved = rememberSaveableStateHolder()
    val navigator = remember(backStack, saved) { Navigator(backStack, saved) }
    val route = navigator.route
    LaunchedEffect(route) { if (route is Route.Home) shell.refresh() }
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = shellHandlesBack(route, navigator.depth),
        onBackCompleted = navigator::handleBack,
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
        val wide = maxWidth >= WIDE_LAYOUT
        CompositionLocalProvider(LocalWideLayout provides wide) {
            Column(Modifier.fillMaxSize()) {
                if (wide) WideTopBar(key, shellState.identity)
                val top = navigator.depth - 1
                val floats = wide && top > 0 && (route is Route.Sender || route is Route.Compose)
                Box(Modifier.weight(1f)) {
                    // One keyed call site for every visible entry, so crossing the breakpoint moves a screen rather than rebuilding it.
                    (if (floats) listOf(top - 1, top) else listOf(top)).forEach { index ->
                        val entryKey = navigator.entryKey(index)
                        key(entryKey) {
                            val entry = navigator[index]
                            val floating = floats && index == top
                            if (floating && entry is Route.Sender) Scrim(navigator::back)
                            Box(if (floating) floating(entry, CorvidTheme.colors.line) else Modifier.fillMaxSize()) {
                                CompositionLocalProvider(LocalBackGuard provides navigator.guard(index)) {
                                    saved.SaveableStateProvider(entryKey) { Page(entry, wide, key, shellState, scope, navigator, ledgers) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Scrim(onDismiss: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(
            CorvidTheme.colors.scrim,
        ).clickable(interactionSource = null, indication = null, onClick = onDismiss),
    )
}

/** Wide, a card is a sheet and compose a panel over the screen they were opened from. */
private fun BoxScope.floating(
    route: Route,
    line: Color,
): Modifier =
    if (route is Route.Sender) {
        Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(540.dp).shadow(16.dp)
    } else {
        val shape = CorvidShape.medium.copy(bottomStart = CornerSize(0), bottomEnd = CornerSize(0))
        Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 24.dp)
            .width(540.dp)
            .height(470.dp)
            .shadow(16.dp, shape)
            .border(1.dp, line, shape)
            .clip(shape)
    }

@Composable
private fun Page(
    route: Route,
    wide: Boolean,
    key: KeyState,
    shellState: ShellState,
    scope: Scope,
    navigator: Navigator,
    ledgers: Ledgers,
) {
    val openSender: (String) -> Unit = { navigator.push(Route.Sender(it)) }
    when (route) {
        is Route.Home ->
            Column {
                if (!wide) NarrowTopBar(key)
                TabsBar(
                    current = route.tab,
                    inboxUnread = shellState.inboxUnread,
                    wide = wide,
                    onSelect = navigator::selectTab,
                    onFilters = { navigator.push(Route.Filters) },
                )
                HomeContent(
                    tab = route.tab,
                    ledgers = ledgers,
                    onOpenEmail = { navigator.push(Route.Reader(it)) },
                    onOpenSender = openSender,
                    onWrite = { navigator.push(Route.Compose(ComposeIntent.New)) },
                )
            }
        is Route.Reader ->
            ReaderScreen(
                remember(scope) { scope.get<MailboxRepository>() },
                route.emailId,
                onBack = navigator::back,
                onCompose = { navigator.push(Route.Compose(it)) },
                onOpenSender = openSender,
            )
        is Route.Compose -> ComposeScreen(remember(scope) { scope.get<ComposeRepository>() }, route.intent, onClose = navigator::back)
        is Route.Filters -> FiltersScreen(remember(scope) { scope.get<FiltersRepository>() }, onBack = navigator::back)
        is Route.Sender ->
            SenderCard(
                mailbox = remember(scope) { scope.get<MailboxRepository>() },
                senders = remember(scope) { scope.get<SenderRepository>() },
                address = route.address,
                wide = wide,
                actions =
                    SenderCardActions(
                        close = navigator::back,
                        openEmail = { navigator.push(Route.Reader(it)) },
                        compose = { navigator.push(Route.Compose(it)) },
                        makeFilter = { navigator.push(Route.Filters) },
                    ),
            )
    }
}

/** Whether back belongs to the shell at all; on the Inbox with nothing pushed it leaves the app. */
internal fun shellHandlesBack(
    route: Route,
    depth: Int,
): Boolean = depth > 1 || route != Route.Home(HomeTab.Inbox)
