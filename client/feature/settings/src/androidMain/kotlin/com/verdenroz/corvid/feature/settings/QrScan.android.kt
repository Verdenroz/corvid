package com.verdenroz.corvid.feature.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.verdenroz.corvid.core.designsystem.CorvidShape
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.PrimaryButton
import com.verdenroz.corvid.feature.settings.resources.Res
import com.verdenroz.corvid.feature.settings.resources.scan_cancel
import com.verdenroz.corvid.feature.settings.resources.scan_hint
import com.verdenroz.corvid.feature.settings.resources.scan_no_camera_permission
import org.jetbrains.compose.resources.stringResource

private enum class ScanState { Idle, Scanning, PermissionDenied }

@Composable
actual fun rememberQrScanLauncher(onResult: (String) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    var state by remember { mutableStateOf(ScanState.Idle) }
    val requestPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            state = if (granted) ScanState.Scanning else ScanState.PermissionDenied
        }

    when (state) {
        ScanState.Idle -> Unit
        ScanState.Scanning ->
            ScanOverlay(onDismiss = { state = ScanState.Idle }, onDecoded = {
                state = ScanState.Idle
                onResult(it)
            })
        ScanState.PermissionDenied ->
            Overlay(onDismiss = { state = ScanState.Idle }) { OverlayText(stringResource(Res.string.scan_no_camera_permission)) }
    }

    if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) return null
    return {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted) state = ScanState.Scanning else requestPermission.launch(Manifest.permission.CAMERA)
    }
}

@Composable
private fun ScanOverlay(
    onDismiss: () -> Unit,
    onDecoded: (String) -> Unit,
) {
    Overlay(onDismiss) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(CorvidShape.medium)) {
            QrScanCamera(onDecoded = onDecoded, modifier = Modifier.fillMaxSize())
        }
        OverlayText(stringResource(Res.string.scan_hint))
    }
}

@Composable
private fun Overlay(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier.fillMaxSize().background(Color.Black).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            content()
            PrimaryButton(stringResource(Res.string.scan_cancel), onClick = onDismiss)
        }
    }
}

@Composable
private fun OverlayText(text: String) {
    Text(text, style = CorvidTheme.type.body, color = Color.White, textAlign = TextAlign.Center)
}
