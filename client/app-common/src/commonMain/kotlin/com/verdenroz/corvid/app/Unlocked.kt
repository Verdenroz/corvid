package com.verdenroz.corvid.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
import com.verdenroz.corvid.app.shell.NavDrawer
import com.verdenroz.corvid.app.shell.Places
import com.verdenroz.corvid.app.shell.SearchInput
import com.verdenroz.corvid.app.shell.SearchTopBar
import com.verdenroz.corvid.app.shell.Sidebar
import com.verdenroz.corvid.app.shell.WideTopBar
import com.verdenroz.corvid.app.shell.sidebarPlace
import com.verdenroz.corvid.app.shell.title
import com.verdenroz.corvid.core.data.AttachmentFiles
import com.verdenroz.corvid.core.data.ComposeRepository
import com.verdenroz.corvid.core.data.DigestRepository
import com.verdenroz.corvid.core.data.FiltersRepository
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.data.NoticeRepository
import com.verdenroz.corvid.core.data.SenderRepository
import com.verdenroz.corvid.core.data.SuggestedRules
import com.verdenroz.corvid.core.designsystem.CorvidFloating
import com.verdenroz.corvid.core.designsystem.CorvidShape
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.LocalWideLayout
import com.verdenroz.corvid.core.designsystem.ProvideWideLayout
import com.verdenroz.corvid.core.designsystem.WIDE_LAYOUT
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.model.ComposeIntent
import com.verdenroz.corvid.core.prefs.DevicePreferences
import com.verdenroz.corvid.core.ui.LocalEntryHandlers
import com.verdenroz.corvid.core.ui.LocalTopEntryKeys
import com.verdenroz.corvid.feature.compose.ComposeScreen
import com.verdenroz.corvid.feature.compose.ComposeViewModel
import com.verdenroz.corvid.feature.digest.DigestScreen
import com.verdenroz.corvid.feature.digest.DigestViewModel
import com.verdenroz.corvid.feature.filters.FiltersScreen
import com.verdenroz.corvid.feature.filters.FiltersViewModel
import com.verdenroz.corvid.feature.mailbox.ActionNoticeBar
import com.verdenroz.corvid.feature.mailbox.MailActions
import com.verdenroz.corvid.feature.mailbox.OvernightOffer
import com.verdenroz.corvid.feature.mailbox.SearchScreen
import com.verdenroz.corvid.feature.mailbox.SenderCard
import com.verdenroz.corvid.feature.mailbox.SenderCardActions
import com.verdenroz.corvid.feature.settings.NoticesScreen
import com.verdenroz.corvid.feature.settings.NoticesViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.core.scope.Scope

private val SIDEBAR_EXPANDS = 1000.dp

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
    val models = remember(scope) { EntryModels() }
    DisposableEffect(models) { onDispose { models.clear() } }

    val autoLock = preferences.settings.collectAsState().value.autoLock.timeout
    DisposableEffect(Unit) {
        idleTimeout.recordActivity()
        onDispose { idleTimeout.stop() }
    }

    val backStack = rememberSaveable(saver = RouteStackSaver) { mutableStateListOf<Route>(Route.Home(HomeTab.Inbox)) }
    val saved = rememberSaveableStateHolder()
    val navigator = remember(backStack, saved, ledgers) { Navigator(backStack, saved, onLeave = ledgers::leave) }
    val newMail = rememberNewMail(scope, navigator)
    val newDigest = rememberNewDigest(scope, navigator)
    FollowPushEndpoint(scope, hosts)
    LaunchedEffect(scope) {
        newMail.check()
        followMailboxChanges(scope.get<JmapClient>().stateChanges(), isOwnEmailState = repository::isOwnEmailState) { echo ->
            shell.refresh()
            ledgers.mailChanged(echo)
            if (!echo) newMail.check()
        }
    }
    val entryKeys = (0 until navigator.depth).map(navigator::entryKey).toSet()
    LaunchedEffect(entryKeys) { models.retainOnly(entryKeys) }
    val home = navigator.route is Route.Home
    LaunchedEffect(home) { if (home) shell.refresh() }
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = navigator.handlesBack,
        onBackCompleted = navigator::handleBack,
    )

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
        val searchInput = rememberSearchInput(navigator, wide)
        val pages = Pages(navigator, saved, scope, models, ledgers, session, newDigest, searchInput.onText)
        val topKeys = remember(navigator) { navigator::handleKey }
        ProvideWideLayout(wide) {
            CompositionLocalProvider(LocalTopEntryKeys provides topKeys) {
                Shell(pages, shellState, key, searchInput, Banners(notices, updates), roomForSidebar = maxWidth >= SIDEBAR_EXPANDS)
            }
        }
    }
}

/** The banners above the shell, highest priority first. */
private class Banners(
    val notices: Notices,
    val updates: AppUpdates,
)

/** What every page on the stack is built from. */
private class Pages(
    val navigator: Navigator,
    val saved: SaveableStateHolder,
    val scope: Scope,
    val models: EntryModels,
    val ledgers: Ledgers,
    val session: SettingsSession,
    val digest: NewDigest,
    val onQuery: (String) -> Unit,
)

@Composable
private fun Shell(
    pages: Pages,
    shellState: ShellState,
    key: KeyState,
    searchInput: SearchInput,
    banners: Banners,
    roomForSidebar: Boolean,
) {
    val navigator = pages.navigator
    val wide = LocalWideLayout.current
    val route = navigator.route
    val shellFocus = remember { ShellFocus() }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    var sidebarChoice by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val sidebarExpanded = sidebarChoice ?: roomForSidebar
    val places =
        Places(
            sidebarPlace(navigator.homeTab, route, navigator.searching),
            shellState.inboxUnread,
            onSelect = navigator::openTab,
            onFilters = { navigator.open(Route.Filters) },
            onSettings = { navigator.open(Route.Settings) },
        )
    val top = navigator.depth - 1
    val floats = wide && top > 0 && (route is Route.Sender || route is Route.Compose)
    LaunchedEffect(wide) { if (wide) drawer.snapTo(DrawerValue.Closed) }
    shellFocus.ReclaimOn(navigator.entryKey(top))
    NavDrawer(drawer, enabled = !wide && route is Route.Home, places) {
        Column(
            Modifier.fillMaxSize().then(shellFocus.modifier(navigator::handleKey)).onPreviewKeyEvent { event ->
                (wide && event.isSearchShortcut()).also { if (it) searchInput.focus.requestFocus() }
            },
        ) {
            NoticesBanner(banners.notices, navigator)
            AppUpdatesBanner(banners.updates)
            if (wide) {
                WideTopBar(
                    key,
                    shellState.identity,
                    searchInput,
                    onOpenSettings = { navigator.open(Route.Settings) },
                    onToggleSidebar = { sidebarChoice = !sidebarExpanded },
                )
            }
            if (!wide && route is Route.Search) SearchTopBar(searchInput, navigator::handleBack)
            Row(Modifier.weight(1f)) {
                if (wide) Sidebar(places, sidebarExpanded, onWrite = { navigator.push(Route.Compose(ComposeIntent.New)) })
                Box(Modifier.weight(1f)) {
                    // One keyed call site for every visible entry, so crossing the breakpoint moves a screen rather than rebuilding it.
                    (if (floats) listOf(top - 1, top) else listOf(top)).forEach { index ->
                        Entry(index, floating = floats && index == top, pages) {
                            Home(
                                it,
                                key,
                                shellState,
                                navigator,
                                pages.ledgers,
                                rememberOvernightOffer(pages.digest, navigator),
                                pages.onQuery,
                                onMenu = { drawerScope.launch { drawer.open() } },
                            )
                        }
                    }
                    Notice(pages.ledgers.actions, composeFloats = floats && route is Route.Compose)
                }
            }
        }
    }
}

@Composable
private fun BoxScope.Entry(
    index: Int,
    floating: Boolean,
    pages: Pages,
    home: @Composable (Route.Home) -> Unit,
) {
    val navigator = pages.navigator
    val entryKey = navigator.entryKey(index)
    key(entryKey) {
        val route = navigator[index]
        if (floating && route is Route.Sender) Scrim(navigator::back)
        Box(if (floating) floating(route, CorvidTheme.colors.line) else Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalEntryHandlers provides navigator.handlers(index)) {
                pages.saved.SaveableStateProvider(entryKey) { Page(route, index, entryKey, pages, home) }
            }
        }
    }
}

@Composable
private fun BoxScope.Notice(
    actions: MailActions,
    composeFloats: Boolean,
) {
    val notice = actions.notice.collectAsState().value ?: return
    val wide = LocalWideLayout.current
    ActionNoticeBar(
        actions,
        notice,
        Modifier
            .align(if (wide) Alignment.BottomStart else Alignment.BottomCenter)
            .padding(
                start = CorvidFloating.noticeStart,
                end = CorvidFloating.noticeEnd(panelShown = composeFloats),
                bottom = CorvidFloating.noticeBottom(wide),
            ),
    )
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
        Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(CorvidFloating.panelWidth).shadow(16.dp)
    } else {
        val shape = CorvidShape.medium.copy(bottomStart = CornerSize(0), bottomEnd = CornerSize(0))
        Modifier
            .align(Alignment.BottomEnd)
            .padding(end = CorvidFloating.panelEnd)
            .width(CorvidFloating.panelWidth)
            .height(CorvidFloating.panelHeight)
            .shadow(16.dp, shape)
            .border(1.dp, line, shape)
            .clip(shape)
    }

@Composable
internal fun Home(
    route: Route.Home,
    key: KeyState,
    shellState: ShellState,
    navigator: Navigator,
    ledgers: Ledgers,
    overnight: OvernightOffer?,
    onQuery: (String) -> Unit,
    onMenu: () -> Unit,
) {
    Column {
        if (!LocalWideLayout.current) {
            NarrowTopBar(
                stringResource(route.tab.title()),
                key,
                shellState.identity,
                onMenu = onMenu,
                onSearch = { navigator.push(Route.Search("")) },
                onOpenSettings = { navigator.push(Route.Settings) },
            )
        }
        HomeContent(
            tab = route.tab,
            ledgers = ledgers,
            overnight = overnight,
            onOpenEmail = navigator::openEmail,
            onOpenSender = { navigator.push(Route.Sender(it)) },
            onSearch = onQuery,
            onWrite = { navigator.push(Route.Compose(ComposeIntent.New)) },
        )
    }
}

@Composable
private fun Page(
    route: Route,
    index: Int,
    entryKey: String,
    pages: Pages,
    home: @Composable (Route.Home) -> Unit,
    files: AttachmentFiles = koinInject(),
) {
    val navigator = pages.navigator
    val scope = pages.scope
    val models = pages.models
    when (route) {
        is Route.Home -> home(route)
        is Route.Reader -> ReaderPage(route, index, entryKey, models, scope, files, pages.ledgers, navigator)
        is Route.Search ->
            SearchScreen(
                viewModel = route.query.takeIf { it.isNotBlank() }?.let { pages.ledgers.search(models, entryKey, it) },
                actions = pages.ledgers.actions,
                query = route.query,
                onOpenEmail = navigator::openEmail,
                onOpenSender = { navigator.push(Route.Sender(it)) },
                onQuery = pages.onQuery,
            )
        is Route.Compose ->
            ComposeScreen(files, route.intent, onClose = navigator::back) { restore ->
                models.of(entryKey, ComposeViewModel::class) { ComposeViewModel(scope.get<ComposeRepository>(), route.intent, restore) }
            }
        is Route.Filters ->
            FiltersScreen(onBack = navigator::back) { draft ->
                models.of(entryKey, FiltersViewModel::class) {
                    FiltersViewModel(scope.get<FiltersRepository>(), scope.get<SuggestedRules>(), draft)
                }
            }
        is Route.Settings -> SettingsPage(scope, pages.session, navigator, models, entryKey)
        is Route.ApproveDevice -> ApproveDevicePage(scope, navigator, models, entryKey)
        is Route.Passkeys -> PasskeysPage(scope, navigator, models, entryKey)
        is Route.Notices -> {
            val viewModel = models.of(entryKey, NoticesViewModel::class) { NoticesViewModel(scope.get<NoticeRepository>()) }
            NoticesScreen(viewModel, onBack = navigator::back)
        }
        is Route.Digest -> {
            val viewModel = models.of(entryKey, DigestViewModel::class) { DigestViewModel(scope.get<DigestRepository>(), route.id) }
            DigestScreen(viewModel, onBack = navigator::back, onOpenEmail = { navigator.push(Route.Reader(it)) })
        }
        is Route.Sender ->
            SenderCard(
                senders = remember(scope) { scope.get<SenderRepository>() },
                suggested = remember(scope) { scope.get<SuggestedRules>() },
                address = route.address,
                wide = LocalWideLayout.current,
                actions =
                    SenderCardActions(
                        close = navigator::back,
                        openEmail = navigator::openEmail,
                        compose = { navigator.push(Route.Compose(it)) },
                        makeFilter = { navigator.push(Route.Filters) },
                    ),
            )
    }
}
