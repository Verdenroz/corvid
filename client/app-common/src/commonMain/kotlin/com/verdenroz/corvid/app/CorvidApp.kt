package com.verdenroz.corvid.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.verdenroz.corvid.core.data.clockTime
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.prefs.DevicePreferences
import com.verdenroz.corvid.core.prefs.ThemeChoice
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.core.ui.ConnectingPane
import com.verdenroz.corvid.core.ui.LockedPane
import com.verdenroz.corvid.core.ui.RevokedPane
import com.verdenroz.corvid.core.ui.UnreachablePane
import com.verdenroz.corvid.feature.signin.SignInScreen
import kotlinx.datetime.TimeZone
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Instant

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
        Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            when (val current = state) {
                is AppLockState.SignedOut -> SignInScreen()
                is AppLockState.Connecting ->
                    if (current.needsDeviceName) {
                        DeviceNamePrompt(onSubmit = viewModel::submitDeviceName)
                    } else {
                        ConnectingPane()
                    }
                is AppLockState.Unlocked -> UnlockedContent(host = viewModel.serverHost.orEmpty(), onLock = viewModel::lock)
                is AppLockState.Unreachable -> UnreachablePane(reason = current.reason, onRetry = viewModel::retry)
                is AppLockState.Locked -> {
                    val facts = viewModel.lockFacts
                    LockedPane(facts.server, facts.device, facts.lockedAt?.let { droppedAt(it) }, onUnlock = viewModel::unlock)
                }
                is AppLockState.Revoked -> RevokedPane()
                is AppLockState.NeedsEnrollment -> EnrollmentPrompt(deviceName = current.deviceName, onConfirm = viewModel::confirmEnroll)
            }
        }
    }
}

@Composable
private fun droppedAt(at: Instant): String = clockTime(at, TimeZone.currentSystemDefault()).asString()
