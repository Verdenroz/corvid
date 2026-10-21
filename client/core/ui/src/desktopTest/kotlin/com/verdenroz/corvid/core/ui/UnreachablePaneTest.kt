package com.verdenroz.corvid.core.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.text.UiText
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class UnreachablePaneTest {
    @Test
    fun `signing out from an unreachable server calls back and does not retry`() =
        runComposeUiTest {
            var retried = 0
            var signedOut = 0
            setContent {
                CorvidTheme(darkTheme = false) {
                    UnreachablePane(UiText.FromServer("the session is unusable"), onRetry = { retried++ }, onSignOut = { signedOut++ })
                }
            }

            onNodeWithText("Sign out").performClick()

            assertEquals(1, signedOut)
            assertEquals(0, retried)
        }
}
