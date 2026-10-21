package com.verdenroz.corvid.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.verdenroz.corvid.core.data.clockTime
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.prefs.DevicePreferences
import com.verdenroz.corvid.core.prefs.ThemeChoice
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.core.ui.ConnectingPane
import com.verdenroz.corvid.core.ui.DeviceApprovalOffPane
import com.verdenroz.corvid.core.ui.KeyDamagedPane
import com.verdenroz.corvid.core.ui.LockedPane
import com.verdenroz.corvid.core.ui.RevokedPane
import com.verdenroz.corvid.core.ui.UnreachablePane
import com.verdenroz.corvid.feature.signin.SignInScreen
import kotlinx.datetime.TimeZone
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Instant

private const val UNLOCK_MILLIS = 220
private const val PANE_MILLIS = 150

/** The lock gate: swaps every route for a pane per [AppLockState]. */
@Composable
fun CorvidApp(
    viewModel: AppLockViewModel = koinViewModel(),
    preferences: DevicePreferences = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    val settings by preferences.settings.collectAsState()
    val dark =
        when (settings.theme) {
            ThemeChoice.System -> isSystemInDarkTheme()
            ThemeChoice.Light -> false
            ThemeChoice.Dark -> true
        }

    CorvidTheme(darkTheme = dark) {
        Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).background(CorvidTheme.colors.surface)) {
            val current = state
            key(current::class) {
                Arrive(if (current is AppLockState.Unlocked) UNLOCK_MILLIS else PANE_MILLIS) {
                    GatePane(current, viewModel)
                }
            }
        }
    }
}

/**
 * Fades a pane in each time the gate moves to a new state. Nothing fades out: the mailbox goes in the same frame as
 * the key, since nothing readable may outlive it and its scope is already closed.
 */
@Composable
private fun Arrive(
    millis: Int,
    content: @Composable () -> Unit,
) {
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(shown, enter = fadeIn(tween(millis, easing = FastOutSlowInEasing)), exit = ExitTransition.None) { content() }
}

@Composable
private fun GatePane(
    current: AppLockState,
    viewModel: AppLockViewModel,
) {
    when (current) {
        is AppLockState.SignedOut -> SignInScreen()
        is AppLockState.Connecting ->
            if (current.needsDeviceName) {
                DeviceNamePrompt(onSubmit = viewModel::submitDeviceName)
            } else {
                ConnectingPane()
            }
        is AppLockState.Unlocked ->
            UnlockedContent(
                hosts = current.hosts,
                onLock = viewModel::lock,
                onSignOut = viewModel::signOut,
            )
        is AppLockState.Unreachable ->
            UnreachablePane(
                reason = current.reason,
                onRetry = viewModel::retry,
                onSignOut = viewModel::signOut,
            )
        is AppLockState.Locked -> {
            val facts = viewModel.lockFacts
            LockedPane(facts.server, facts.device, facts.lockedAt?.let { droppedAt(it) }, onUnlock = viewModel::unlock)
        }
        is AppLockState.DeviceApprovalOff -> DeviceApprovalOffPane(onAskAgain = viewModel::askAgain, onSignOut = viewModel::signOut)
        is AppLockState.KeyDamaged -> KeyDamagedPane(onRetry = viewModel::retry, onSignOut = viewModel::signOut)
        is AppLockState.Revoked -> RevokedPane()
        is AppLockState.NeedsEnrollment ->
            EnrollmentPrompt(
                deviceName = current.deviceName,
                request = current.request,
                onAskAgain = viewModel::askAgain,
                onApproveSelf = viewModel::approveWithAdminPasskey,
                onWatch = viewModel::watchRequest,
                onSignOut = viewModel::signOut,
            )
    }
}

@Composable
private fun droppedAt(at: Instant): String = clockTime(at, TimeZone.currentSystemDefault()).asString()
