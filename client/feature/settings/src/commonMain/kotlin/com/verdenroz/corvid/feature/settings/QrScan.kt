package com.verdenroz.corvid.feature.settings

import androidx.compose.runtime.Composable

/**
 * A launcher that opens the platform's QR scanner and calls [onResult] with the decoded text; a
 * cancelled scan calls nothing. Null where the device cannot scan, so the screen offers manual entry only.
 */
@Composable
expect fun rememberQrScanLauncher(onResult: (String) -> Unit): (() -> Unit)?
