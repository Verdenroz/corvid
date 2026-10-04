package com.verdenroz.corvid.core.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

private const val HUE = 295f
private const val CHROMA = 0.16f

/**
 * The Raven palette: ink and paper tinted toward the feather's violet, with [sheenStart] and
 * [sheenEnd] as the violet-to-green throw that only the mark, the key ring and the chain of
 * custody edge may use. [rail] sits one step off [surface] for bars and side sheets.
 */
@Immutable
data class CorvidColors(
    val rail: Color,
    val surface: Color,
    val hover: Color,
    val line: Color,
    val ink: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    val accent: Color,
    val accentSoft: Color,
    val onAccentSoft: Color,
    val onAccent: Color,
    val chip: Color,
    val danger: Color,
    val dangerSoft: Color,
    val onDangerSoft: Color,
    val dangerTint: Color,
    val sheenStart: Color,
    val sheenEnd: Color,
    val isDark: Boolean,
)

val DarkCorvidColors =
    CorvidColors(
        rail = oklch(0.18f, 0.012f, HUE),
        surface = oklch(0.205f, 0.012f, HUE),
        hover = oklch(0.26f, 0.014f, HUE),
        line = oklch(0.30f, 0.012f, HUE),
        ink = oklch(0.93f, 0.008f, HUE),
        inkMuted = oklch(0.73f, 0.012f, HUE),
        inkFaint = oklch(0.58f, 0.012f, HUE),
        accent = oklch(0.76f, CHROMA * 0.72f, HUE),
        accentSoft = oklch(0.34f, CHROMA * 0.36f, HUE),
        onAccentSoft = oklch(0.92f, CHROMA * 0.28f, HUE),
        onAccent = oklch(0.20f, 0.02f, HUE),
        chip = oklch(0.30f, CHROMA * 0.30f, HUE),
        danger = oklch(0.72f, 0.15f, 28f),
        dangerSoft = oklch(0.30f, 0.06f, 28f),
        onDangerSoft = oklch(0.92f, 0.04f, 28f),
        dangerTint = oklch(0.24f, 0.035f, 28f),
        sheenStart = oklch(0.70f, 0.15f, 300f),
        sheenEnd = oklch(0.74f, 0.12f, 160f),
        isDark = true,
    )

val LightCorvidColors =
    CorvidColors(
        rail = oklch(0.972f, 0.007f, HUE),
        surface = oklch(0.996f, 0.002f, HUE),
        hover = oklch(0.94f, 0.012f, HUE),
        line = oklch(0.905f, 0.01f, HUE),
        ink = oklch(0.24f, 0.02f, HUE),
        inkMuted = oklch(0.47f, 0.02f, HUE),
        inkFaint = oklch(0.62f, 0.015f, HUE),
        accent = oklch(0.52f, CHROMA, HUE),
        accentSoft = oklch(0.92f, CHROMA * 0.30f, HUE),
        onAccentSoft = oklch(0.30f, CHROMA * 0.75f, HUE),
        onAccent = oklch(0.99f, 0.005f, HUE),
        chip = oklch(0.93f, CHROMA * 0.24f, HUE),
        danger = oklch(0.55f, 0.19f, 28f),
        dangerSoft = oklch(0.95f, 0.03f, 28f),
        onDangerSoft = oklch(0.36f, 0.12f, 28f),
        dangerTint = oklch(0.975f, 0.015f, 28f),
        sheenStart = oklch(0.58f, 0.17f, 300f),
        sheenEnd = oklch(0.64f, 0.13f, 160f),
        isDark = false,
    )

internal fun CorvidColors.toMaterial(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentSoft,
        onPrimaryContainer = onAccentSoft,
        secondary = accent,
        onSecondary = onAccent,
        secondaryContainer = accentSoft,
        onSecondaryContainer = onAccentSoft,
        tertiary = accent,
        onTertiary = onAccent,
        background = surface,
        onBackground = ink,
        surface = surface,
        onSurface = ink,
        surfaceVariant = hover,
        onSurfaceVariant = inkMuted,
        surfaceTint = surface,
        surfaceContainerLowest = surface,
        surfaceContainerLow = rail,
        surfaceContainer = rail,
        surfaceContainerHigh = hover,
        surfaceContainerHighest = hover,
        outline = inkFaint,
        outlineVariant = line,
        error = danger,
        onError = onAccent,
        errorContainer = dangerSoft,
        onErrorContainer = onDangerSoft,
    )
}
