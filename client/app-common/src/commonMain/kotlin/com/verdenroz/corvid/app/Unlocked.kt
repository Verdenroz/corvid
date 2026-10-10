package com.verdenroz.corvid.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.verdenroz.corvid.app.shell.HomeContent
import com.verdenroz.corvid.app.shell.KeyState
import com.verdenroz.corvid.app.shell.NarrowTopBar
import com.verdenroz.corvid.app.shell.SearchTopBar
import com.verdenroz.corvid.app.shell.TabsBar
import com.verdenroz.corvid.app.shell.WideTopBar
import com.verdenroz.corvid.core.data.ComposeRepository
import com.verdenroz.corvid.core.data.FiltersRepository
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.data.NoticeRepository
import com.verdenroz.corvid.core.data.SenderRepository
import com.verdenroz.corvid.core.designsystem.CorvidShape
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.model.ComposeIntent
import com.verdenroz.corvid.core.prefs.DevicePreferences
import com.verdenroz.corvid.core.ui.LocalBackGuard
import com.verdenroz.corvid.core.ui.LocalWideLayout
import com.verdenroz.corvid.core.ui.WIDE_LAYOUT
import com.verdenroz.corvid.feature.compose.ComposeScreen
import com.verdenroz.corvid.feature.filters.FiltersScreen
import com.verdenroz.corvid.feature.mailbox.LedgerSource
import com.verdenroz.corvid.feature.mailbox.LedgerTab
import com.verdenroz.corvid.feature.mailbox.LedgerViewModel
import com.verdenroz.corvid.feature.mailbox.ReaderScreen
import com.verdenroz.corvid.feature.mailbox.SearchScreen
import com.verdenroz.corvid.feature.mailbox.SenderCard
import com.verdenroz.corvid.feature.mailbox.SenderCardActions
import com.verdenroz.corvid.feature.settings.NoticesScreen
import com.verdenroz.corvid.feature.settings.NoticesViewModel
import org.koin.compose.koinInject
import org.koin.core.scope.Scope

private fun KeyEvent.isSearchShortcut() = type == KeyEventType.KeyDown && key == Key.K && (isCtrlPressed || isMetaPressed)

/** Every pointer event here resets the idle timer. */
@Composable
internal fun UnlockedContent(
    hosts: AccountHosts,
    onLock: () -> Unit,
    onSignOut: () -> Unit,
    idleTimeout: IdleTimeoutController = koinInject(),
    preferences: DevicePreferences = koinInject(),
    scopeHolder: UnlockedScopeHolder = koinInject(),
) {
    val scope = scopeHolder.current()
    val repository = remember(scope) { scope.get<MailboxRepository>() }
    val shell = remember(scope) { ShellViewModel(repository) }
    val notices = rememberNotices(scope)
    val updates = rememberAppUpdates(scope)
    val ledgers = remember(repository) { Ledgers(repository) }
    DisposableEffect(ledgers) { onDispose { ledgers.clear() } }
    LaunchedEffect(scope) {
        followMailboxChanges(scope.get<JmapClient>().stateChanges()) {
            shell.refresh()
            ledgers.refreshAll()
        }
    }
    val models = remember(scope) { EntryModels() }
    DisposableEffect(models) { onDispose { models.clear() } }

    val autoLock = preferences.settings.collectAsState().value.autoLock.timeout
    DisposableEffect(Unit) {
        idleTimeout.recordActivity()
        onDispose { idleTimeout.stop() }
    }

    val backStack = rememberSaveable(saver = RouteStackSaver) { mutableStateListOf<Route>(Route.Home(HomeTab.Inbox)) }
    val saved = rememberSaveableStateHolder()
    val navigator = remember(backStack, saved) { Navigator(backStack, saved) }
    val route = navigator.route
    val entryKeys = (0 until navigator.depth).map(navigator::entryKey).toSet()
    LaunchedEffect(entryKeys) { models.retainOnly(entryKeys) }
    val home = route is Route.Home
    LaunchedEffect(home) { if (home) shell.refresh() }
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = shellHandlesBack(route, navigator.depth),
        onBackCompleted = navigator::handleBack,
    )

    val searchInput = rememberSearchInput(navigator)
    val shellFocus = remember { FocusRequester() }

    val shellState by shell.state.collectAsState()
    val session = SettingsSession(hosts, shellState.identity, updates, onLock, onSignOut)
    val key = remember(hosts, autoLock, onLock) { KeyState(hosts.short, idleTimeout.locksAt, autoLock, onLock) }
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
            LaunchedEffect(Unit) { shellFocus.requestFocus() }
            Column(
                Modifier.fillMaxSize().focusRequester(shellFocus).focusable().onPreviewKeyEvent { event ->
                    (wide && event.isSearchShortcut()).also { if (it) searchInput.focus.requestFocus() }
                },
            ) {
                NoticesBanner(notices, navigator)
                AppUpdatesBanner(updates)
                if (wide) {
                    WideTopBar(
                        key,
                        shellState.identity,
                        searchInput,
                        onOpenSettings = { if (route != Route.Settings) navigator.push(Route.Settings) },
                    )
                }
                if (!wide && route is Route.Search) SearchTopBar(searchInput, navigator::handleBack)
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
                                    saved.SaveableStateProvider(entryKey) {
                                        Page(
                                            entry,
                                            navigator,
                                            scope,
                                            models,
                                            entryKey,
                                            session,
                                        ) { Home(it, key, shellState, navigator, ledgers) }
                                    }
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
private fun Home(
    route: Route.Home,
    key: KeyState,
    shellState: ShellState,
    navigator: Navigator,
    ledgers: Ledgers,
) {
    val wide = LocalWideLayout.current
    LaunchedEffect(route.tab) { if (route.tab == HomeTab.Drafts) ledgers.of(LedgerTab.Drafts).refresh() }
    Column {
        if (!wide) {
            NarrowTopBar(
                key,
                shellState.identity,
                onSearch = { navigator.push(Route.Search("")) },
                onOpenSettings = { navigator.push(Route.Settings) },
            )
        }
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
            onOpenEmail = { navigator.push(if (route.tab == HomeTab.Drafts) Route.Compose(ComposeIntent.Draft(it)) else Route.Reader(it)) },
            onOpenSender = { navigator.push(Route.Sender(it)) },
            onWrite = { navigator.push(Route.Compose(ComposeIntent.New)) },
        )
    }
}

@Composable
private fun Page(
    route: Route,
    navigator: Navigator,
    scope: Scope,
    models: EntryModels,
    entryKey: String,
    session: SettingsSession,
    home: @Composable (Route.Home) -> Unit,
) {
    val mailbox = remember(scope) { scope.get<MailboxRepository>() }
    when (route) {
        is Route.Home -> home(route)
        is Route.Reader ->
            ReaderScreen(
                mailbox,
                route.emailId,
                onBack = navigator::back,
                onCompose = { navigator.push(Route.Compose(it)) },
                onOpenSender = { navigator.push(Route.Sender(it)) },
            )
        is Route.Search ->
            SearchScreen(
                viewModel =
                    if (route.query.isBlank()) {
                        null
                    } else {
                        models.of(entryKey, LedgerViewModel::class) {
                            LedgerViewModel(mailbox, LedgerSource.Search(route.query))
                        }
                    },
                query = route.query,
                onOpenEmail = { navigator.push(Route.Reader(it)) },
                onOpenSender = { navigator.push(Route.Sender(it)) },
            )
        is Route.Compose -> ComposeScreen(remember(scope) { scope.get<ComposeRepository>() }, route.intent, onClose = navigator::back)
        is Route.Filters -> FiltersScreen(remember(scope) { scope.get<FiltersRepository>() }, onBack = navigator::back)
        is Route.Settings -> SettingsPage(scope, session, navigator, models, entryKey)
        is Route.ApproveDevice -> ApproveDevicePage(scope, navigator, models, entryKey)
        is Route.Notices -> {
            val viewModel = models.of(entryKey, NoticesViewModel::class) { NoticesViewModel(scope.get<NoticeRepository>()) }
            NoticesScreen(viewModel, onBack = navigator::back)
        }
        is Route.Sender ->
            SenderCard(
                senders = remember(scope) { scope.get<SenderRepository>() },
                address = route.address,
                wide = LocalWideLayout.current,
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
