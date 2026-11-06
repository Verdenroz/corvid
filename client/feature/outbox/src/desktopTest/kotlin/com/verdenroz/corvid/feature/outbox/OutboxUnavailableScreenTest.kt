package com.verdenroz.corvid.feature.outbox

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class OutboxUnavailableScreenTest {
    @Test
    fun `an outbox held by another window shows that instead of a list`() =
        runComposeUiTest {
            setContent { CorvidTheme(darkTheme = false) { OutboxUnavailableScreen(held = true, onBack = {}) } }

            onNodeWithText("Another Corvid window is using the outbox").assertExists()
        }

    @Test
    fun `an outbox that could not be opened says so`() =
        runComposeUiTest {
            setContent { CorvidTheme(darkTheme = false) { OutboxUnavailableScreen(held = false, onBack = {}) } }

            onNodeWithText("The outbox could not be opened").assertExists()
        }
}
