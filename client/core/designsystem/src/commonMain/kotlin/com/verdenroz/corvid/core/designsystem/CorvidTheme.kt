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

private val LocalCorvidColors = staticCompositionLocalOf { LightCorvidColors }
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
        MaterialTheme(colorScheme = colors.toMaterial(), typography = type.toMaterial(), shapes = CorvidShapes) {
            Surface(modifier = Modifier.fillMaxSize(), color = colors.surface, contentColor = colors.ink, content = content)
        }
    }
}

object CorvidTheme {
    val colors: CorvidColors
        @Composable @ReadOnlyComposable
        get() = LocalCorvidColors.current

    val type: CorvidType
        @Composable @ReadOnlyComposable
        get() = LocalCorvidType.current
}
