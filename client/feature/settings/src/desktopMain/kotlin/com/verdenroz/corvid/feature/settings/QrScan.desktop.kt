package com.verdenroz.corvid.feature.settings

import androidx.compose.runtime.Composable

@Composable
actual fun rememberQrScanLauncher(onResult: (String) -> Unit): (() -> Unit)? = null
