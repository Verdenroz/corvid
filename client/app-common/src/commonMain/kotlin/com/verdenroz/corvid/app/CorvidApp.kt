package com.verdenroz.corvid.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.ui.LockedPane
import com.verdenroz.corvid.core.ui.RevokedPane
import com.verdenroz.corvid.core.ui.UnreachablePane
import com.verdenroz.corvid.feature.signin.SignInScreen
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
                is AppLockState.Unlocked -> UnlockedContent(host = viewModel.serverHost.orEmpty(), onLock = viewModel::lock)
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
