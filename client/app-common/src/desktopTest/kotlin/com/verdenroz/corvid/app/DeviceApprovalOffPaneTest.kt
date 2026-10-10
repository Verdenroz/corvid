package com.verdenroz.corvid.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DeviceApprovalOffPaneTest {
    @Test
    fun `the pane says device approval is off`() =
        runComposeUiTest {
            setContent { CorvidTheme(darkTheme = false) { DeviceApprovalOffPane(onAskAgain = {}) } }

            onNodeWithText("Device approval is off").assertIsDisplayed()
        }

    @Test
    fun `asking again calls back`() =
        runComposeUiTest {
            var asked = 0
            setContent { CorvidTheme(darkTheme = false) { DeviceApprovalOffPane(onAskAgain = { asked++ }) } }

            onNodeWithText("Ask again").performClick()

            assertEquals(1, asked)
        }
}
