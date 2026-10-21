package com.verdenroz.corvid.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush

internal val LocalCorvidColors = staticCompositionLocalOf { LightCorvidColors }
private val LocalCorvidType = staticCompositionLocalOf<CorvidType> { error("CorvidType read outside CorvidTheme") }

/** Material supplies behaviour and accessibility underneath; colour, type and shape are Corvid's. */
@Composable
fun CorvidTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkCorvidColors else LightCorvidColors
    val type = corvidType(darkTheme)
    CompositionLocalProvider(LocalCorvidColors provides colors, LocalCorvidType provides type) {
        MaterialTheme(colorScheme = colors.toMaterial(), typography = type.toMaterial(), shapes = MaterialShapes) {
            Surface(modifier = Modifier.fillMaxSize(), color = colors.surface, contentColor = colors.ink, content = content)
        }
    }
}

object CorvidTheme {
    val colors: CorvidColors
        @Composable @ReadOnlyComposable
        get() = LocalCorvidColors.current

    /** The feather's throw, violet held to 70% so it still reads violet; for the mark, the key ring and the custody edge only. */
    val sheen: Brush
        @Composable @ReadOnlyComposable
        get() = colors.let { Brush.linearGradient(0f to it.sheenStart, 0.7f to it.accent, 1f to it.sheenEnd) }

    val type: CorvidType
        @Composable @ReadOnlyComposable
        get() = LocalCorvidType.current
}
