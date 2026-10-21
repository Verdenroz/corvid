package com.verdenroz.corvid.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertTrue

class ContrastTest {
    private val themes = mapOf("light" to LightCorvidColors, "dark" to DarkCorvidColors)

    @Test
    fun `every text ink reads as small text on every paper it sits on`() {
        themes.forEach { (theme, colors) ->
            listOf("ink" to colors.ink, "inkMuted" to colors.inkMuted, "inkFaint" to colors.inkFaint).forEach { (name, ink) ->
                papers(colors).forEach { (paper, background) -> assertAtLeast(4.5, ink, background, "$theme $name on $paper") }
            }
        }
    }

    @Test
    fun `danger reads as small text on paper and on its own tints`() {
        themes.forEach { (theme, colors) ->
            (papers(colors) + listOf("dangerSoft" to colors.dangerSoft, "dangerTint" to colors.dangerTint)).forEach { (paper, background) ->
                assertAtLeast(4.5, colors.danger, background, "$theme danger on $paper")
            }
        }
    }

    @Test
    fun `the outline marks a control on every paper it sits on`() {
        themes.forEach { (theme, colors) ->
            papers(colors).forEach { (paper, background) -> assertAtLeast(3.0, colors.outline, background, "$theme outline on $paper") }
        }
    }

    @Test
    fun `the accent focus ring stands out from every paper`() {
        themes.forEach { (theme, colors) ->
            papers(colors).forEach { (paper, background) -> assertAtLeast(3.0, colors.accent, background, "$theme accent on $paper") }
        }
    }

    private fun papers(colors: CorvidColors) = listOf("surface" to colors.surface, "rail" to colors.rail, "hover" to colors.hover)

    private fun assertAtLeast(
        minimum: Double,
        foreground: Color,
        background: Color,
        what: String,
    ) {
        val (a, b) = foreground.luminance() to background.luminance()
        val ratio = (max(a, b) + 0.05) / (min(a, b) + 0.05)
        assertTrue(ratio >= minimum, "$what is ${ratio.toString().take(4)}:1, under $minimum:1")
    }
}
