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
class DeviceApprovalOffPaneTest {
    @Test
    fun `the pane says device approval is off`() =
        runComposeUiTest {
            setContent { CorvidTheme(darkTheme = false) { DeviceApprovalOffPane(onAskAgain = {}, onSignOut = {}) } }

            onNodeWithText("Device approval is off").assertIsDisplayed()
        }

    @Test
    fun `asking again calls back and does not sign out`() =
        runComposeUiTest {
            var asked = 0
            var signedOut = 0
            setContent { CorvidTheme(darkTheme = false) { DeviceApprovalOffPane(onAskAgain = { asked++ }, onSignOut = { signedOut++ }) } }

            onNodeWithText("Ask again").performClick()

            assertEquals(1, asked)
            assertEquals(0, signedOut)
        }

    @Test
    fun `signing out calls back and does not ask again`() =
        runComposeUiTest {
            var asked = 0
            var signedOut = 0
            setContent { CorvidTheme(darkTheme = false) { DeviceApprovalOffPane(onAskAgain = { asked++ }, onSignOut = { signedOut++ }) } }

            onNodeWithText("Sign out").performClick()

            assertEquals(1, signedOut)
            assertEquals(0, asked)
        }
}
