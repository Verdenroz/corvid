package com.verdenroz.corvid.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.verdenroz.corvid.appcommon.resources.Res
import com.verdenroz.corvid.appcommon.resources.app_filters
import com.verdenroz.corvid.appcommon.resources.app_lock
import com.verdenroz.corvid.core.data.ComposeRepository
import com.verdenroz.corvid.core.data.FiltersRepository
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.model.ComposeIntent
import com.verdenroz.corvid.core.ui.LockedPane
import com.verdenroz.corvid.core.ui.RevokedPane
import com.verdenroz.corvid.core.ui.UnreachablePane
import com.verdenroz.corvid.feature.compose.ComposeScreen
import com.verdenroz.corvid.feature.filters.FiltersScreen
import com.verdenroz.corvid.feature.mailbox.InboxScreen
import com.verdenroz.corvid.feature.mailbox.ReaderScreen
import com.verdenroz.corvid.feature.signin.SignInScreen
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/** The lock gate: swaps every route for a pane per [AppLockState]. */
@Composable
fun CorvidApp(viewModel: AppLockViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsState()

    CorvidTheme {
        Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            when (val current = state) {
                is AppLockState.SignedOut -> SignInScreen()
                is AppLockState.Connecting ->
                    if (current.needsDeviceName) {
                        DeviceNamePrompt(onSubmit = viewModel::submitDeviceName)
                    } else {
                        ConnectingPane()
                    }
                is AppLockState.Unlocked -> UnlockedContent(onLock = viewModel::lock)
                is AppLockState.Unreachable -> UnreachablePane(reason = current.reason, onRetry = viewModel::retry)
                is AppLockState.Locked -> LockedPane(onUnlock = viewModel::unlock)
                is AppLockState.Revoked -> RevokedPane()
                is AppLockState.NeedsEnrollment -> EnrollmentPrompt(deviceName = current.deviceName, onConfirm = viewModel::confirmEnroll)
            }
        }
    }
}

@Composable
private fun ConnectingPane() {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            CircularProgressIndicator()
        }
    }
}

/** Every pointer event here resets the idle timer. */
@Composable
private fun UnlockedContent(
    onLock: () -> Unit,
    idleTimeout: IdleTimeoutController = koinInject(),
    scopeHolder: UnlockedScopeHolder = koinInject(),
) {
    val scope = scopeHolder.current()
    val repository = remember(scope) { scope.get<MailboxRepository>() }
    val composeRepository = remember(scope) { scope.get<ComposeRepository>() }
    val filtersRepository = remember(scope) { scope.get<FiltersRepository>() }

    DisposableEffect(Unit) {
        idleTimeout.recordActivity()
        onDispose { idleTimeout.stop() }
    }

    Column(
        modifier =
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent()
                        idleTimeout.recordActivity()
                    }
                }
            },
    ) {
        val backStack = rememberSaveable(saver = RouteStackSaver) { mutableStateListOf<Route>(Route.Inbox) }
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            if (Route.Filters !in backStack) {
                Button(
                    onClick = { backStack += Route.Filters },
                ) { Text(stringResource(Res.string.app_filters)) }
            }
            Button(onClick = onLock) { Text(stringResource(Res.string.app_lock)) }
        }
        val pop = { backStack.removeAt(backStack.lastIndex) }
        NavigationBackHandler(
            state = rememberNavigationEventState(NavigationEventInfo.None),
            isBackEnabled = backStack.size > 1,
            onBackCompleted = { pop() },
        )
        when (val route = backStack.last()) {
            is Route.Inbox ->
                InboxScreen(
                    repository,
                    onOpenEmail = { backStack += Route.Reader(it) },
                    onCompose = { backStack += Route.Compose(ComposeIntent.New) },
                )
            is Route.Reader ->
                ReaderScreen(
                    repository,
                    route.emailId,
                    onBack = { pop() },
                    onCompose = { backStack += Route.Compose(it) },
                )
            is Route.Compose -> ComposeScreen(composeRepository, route.intent, onClose = { pop() })
            is Route.Filters -> FiltersScreen(filtersRepository, onBack = { pop() })
        }
    }
}
