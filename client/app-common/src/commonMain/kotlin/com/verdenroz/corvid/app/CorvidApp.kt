package com.verdenroz.corvid.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.ui.LockedPane
import com.verdenroz.corvid.core.ui.RevokedPane
import com.verdenroz.corvid.core.ui.UnreachablePane
import com.verdenroz.corvid.feature.signin.SignInScreen
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * The lock gate: swaps every route for a pane per [AppLockState]. Unlocked's
 * own screens (mailbox, reader) are slices 7 and 8; this placeholder just
 * proves the gate opens and closes around them.
 */
@Composable
fun CorvidApp(viewModel: AppLockViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsState()

    CorvidTheme {
        when (val current = state) {
            is AppLockState.SignedOut -> SignInScreen()
            is AppLockState.Connecting ->
                if (current.needsDeviceName) {
                    DeviceNamePrompt(onSubmit = viewModel::submitDeviceName)
                } else {
                    ConnectingPane()
                }
            is AppLockState.Unlocked -> UnlockedPlaceholder(onLock = viewModel::lock)
            is AppLockState.Unreachable -> UnreachablePane(reason = current.reason, onRetry = viewModel::retry)
            is AppLockState.Locked -> LockedPane(onUnlock = viewModel::unlock)
            is AppLockState.Revoked -> RevokedPane()
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

/**
 * Every pointer event here resets decision 11's idle timer: once slice 7
 * replaces this with the real Unlocked screens, the tracking modifier moves
 * with it rather than staying on a placeholder.
 */
@Composable
private fun UnlockedPlaceholder(
    onLock: () -> Unit,
    idleTimeout: IdleTimeoutController = koinInject(),
) {
    DisposableEffect(Unit) {
        idleTimeout.recordActivity()
        onDispose { idleTimeout.stop() }
    }

    Surface(
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
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Unlocked", style = MaterialTheme.typography.titleMedium)
                Button(onClick = onLock) { Text("Lock") }
            }
        }
    }
}
