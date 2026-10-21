package com.verdenroz.corvid.core.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class QuietPaneTest {
    @Test
    fun `a failure with a retry offers trying again`() =
        runComposeUiTest {
            var retried = 0
            setContent { CorvidTheme(darkTheme = false) { QuietPane("The server did not answer.", onRetry = { retried++ }) } }

            onNodeWithText("Try again").performClick()

            assertEquals(1, retried)
        }

    @Test
    fun `a load stays blank at first and then says it is waiting`() =
        runComposeUiTest {
            mainClock.autoAdvance = false
            setContent { CorvidTheme(darkTheme = false) { LoadingPane() } }

            mainClock.advanceTimeBy(300)
            onNodeWithText("Waiting for the server…").assertDoesNotExist()

            mainClock.advanceTimeBy(600)
            onNodeWithText("Waiting for the server…").assertIsDisplayed()
        }
}
