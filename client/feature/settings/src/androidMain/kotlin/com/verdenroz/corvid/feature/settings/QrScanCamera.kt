package com.verdenroz.corvid.feature.settings

import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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
import java.util.concurrent.Executors

// CameraX's 640x480 default leaves a code under three pixels per module once it stops filling the viewfinder.
private val ANALYSIS_RESOLUTION = Size(1920, 1080)

/** Decodes the luminance plane of each frame it is handed; CameraX drops frames that arrive while one is being decoded. */
private class QrAnalyzer(private val onDecoded: (String) -> Unit) : ImageAnalysis.Analyzer {
    override fun analyze(image: ImageProxy) {
        image.use {
            val plane = it.planes[0]
            val luminance = ByteArray(plane.buffer.remaining()).also(plane.buffer::get)
            decodeQr(luminance, plane.rowStride, it.width, it.height)?.let(onDecoded)
        }
    }
}

/** A full-bleed camera preview that reports the first QR code it decodes. */
@Composable
internal fun QrScanCamera(
    onDecoded: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnDecoded by rememberUpdatedState(onDecoded)

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
        val decoding = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        var decoded = false
        controller.setImageAnalysisAnalyzer(
            decoding,
            QrAnalyzer { text ->
                main.execute {
                    if (!decoded) {
                        decoded = true
                        currentOnDecoded(text)
                    }
                }
            },
        )
        controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            decoding.shutdown()
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
