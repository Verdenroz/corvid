package com.verdenroz.corvid.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.LightCorvidColors
import com.verdenroz.corvid.core.designsystem.component.PillButton
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FocusRingTest {
    @Test
    fun `a pill focused from the keyboard draws an accent ring outside its edge`() =
        runComposeUiTest {
            showPill()

            onNodeWithTag(PILL).requestFocus()

            assertNear(LightCorvidColors.accent, pixelLeftOfPill())
        }

    @Test
    fun `an unfocused pill draws no ring`() =
        runComposeUiTest {
            showPill()

            assertNear(LightCorvidColors.surface, pixelLeftOfPill())
        }

    @Test
    fun `a pill focused in touch mode draws no ring`() =
        runComposeUiTest {
            var inputModes: InputModeManager? = null
            showPill { inputModes = it }
            runOnIdle { inputModes?.requestInputMode(InputMode.Touch) }

            onNodeWithTag(PILL).requestFocus()

            assertEquals(InputMode.Touch, inputModes?.inputMode)
            assertNear(LightCorvidColors.surface, pixelLeftOfPill())
        }

    private fun ComposeUiTest.showPill(onInputModes: (InputModeManager) -> Unit = {}) {
        setContent {
            onInputModes(LocalInputModeManager.current)
            CorvidTheme(darkTheme = false) {
                Box(Modifier.padding(16.dp)) { PillButton("Retry", null, {}, Modifier.testTag(PILL)) }
            }
        }
    }

    /** Three dp left of the pill's edge at its middle: inside the ring, which starts 2dp out and is 2dp wide. */
    private fun ComposeUiTest.pixelLeftOfPill(): Color {
        val bounds = onNodeWithTag(PILL).fetchSemanticsNode().boundsInRoot
        val x = (bounds.left - 3.dp.value * density.density).roundToInt()
        val y = bounds.center.y.roundToInt()
        return onRoot().captureToImage().toPixelMap()[x, y]
    }

    private fun assertNear(
        expected: Color,
        actual: Color,
    ) {
        val close = listOf(expected.red - actual.red, expected.green - actual.green, expected.blue - actual.blue).all { abs(it) < 0.03f }
        assertTrue(close, "expected $expected, got $actual")
    }

    private companion object {
        const val PILL = "pill"
    }
}
