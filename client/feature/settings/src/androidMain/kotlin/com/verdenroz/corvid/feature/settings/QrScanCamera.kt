package com.verdenroz.corvid.feature.settings

import android.util.Size
import androidx.camera.core.ImageAnalysis.COORDINATE_SYSTEM_ORIGINAL
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController.IMAGE_ANALYSIS
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode

// CameraX's 640x480 default leaves a code under three pixels per module once it stops filling the viewfinder.
private val ANALYSIS_RESOLUTION = Size(1920, 1080)

/** A full-bleed camera preview that reports the first QR code it decodes. */
@Composable
internal fun QrScanCamera(
    onDecoded: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnDecoded by rememberUpdatedState(onDecoded)

    val scanner = remember { BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()) }
    DisposableEffect(scanner) { onDispose { scanner.close() } }

    val controller =
        remember {
            LifecycleCameraController(context).apply {
                setEnabledUseCases(IMAGE_ANALYSIS)
                imageAnalysisResolutionSelector =
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(ANALYSIS_RESOLUTION, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                        )
                        .build()
            }
        }

    DisposableEffect(controller, lifecycleOwner) {
        val executor = ContextCompat.getMainExecutor(context)
        var decoded = false
        controller.setImageAnalysisAnalyzer(
            executor,
            MlKitAnalyzer(listOf(scanner), COORDINATE_SYSTEM_ORIGINAL, executor) { result ->
                if (decoded) return@MlKitAnalyzer
                val value = result.getValue(scanner)?.firstNotNullOfOrNull { it.rawValue }
                if (value != null) {
                    decoded = true
                    currentOnDecoded(value)
                }
            },
        )
        controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                this.controller = controller
            }
        },
    )
}
