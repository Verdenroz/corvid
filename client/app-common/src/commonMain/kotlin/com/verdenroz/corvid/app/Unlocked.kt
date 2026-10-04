package com.verdenroz.corvid.app

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
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
import com.verdenroz.corvid.core.model.ComposeIntent
import com.verdenroz.corvid.feature.compose.ComposeScreen
import com.verdenroz.corvid.feature.filters.FiltersScreen
import com.verdenroz.corvid.feature.mailbox.ReaderScreen
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
        isBackEnabled = backStack.size > 1 || route != Route.Home(HomeTab.Inbox),
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
            when (route) {
                is Route.Home -> {
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
                        onWrite = { backStack += Route.Compose(ComposeIntent.New) },
                    )
                }
                else -> Pushed(route, scope, repository, backStack)
            }
        }
    }
}

@Composable
private fun Pushed(
    route: Route,
    scope: Scope,
    repository: MailboxRepository,
    backStack: SnapshotStateList<Route>,
) {
    when (route) {
        is Route.Reader ->
            ReaderScreen(
                repository,
                route.emailId,
                onBack = { backStack.back() },
                onCompose = { backStack += Route.Compose(it) },
            )
        is Route.Compose -> ComposeScreen(remember(scope) { scope.get<ComposeRepository>() }, route.intent, onClose = { backStack.back() })
        is Route.Filters -> FiltersScreen(remember(scope) { scope.get<FiltersRepository>() }, onBack = { backStack.back() })
        is Route.Home -> Unit
    }
}

/** Pops a pushed screen; from a home tab other than Inbox, returns to Inbox. */
private fun SnapshotStateList<Route>.back() {
    if (size > 1) removeAt(lastIndex) else this[0] = Route.Home(HomeTab.Inbox)
}
