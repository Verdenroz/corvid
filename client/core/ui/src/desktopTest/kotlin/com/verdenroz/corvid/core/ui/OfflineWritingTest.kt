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
class OfflineWritingTest {
    @Test
    fun `the locked pane offers writing a message when it is given a way to`() =
        runComposeUiTest {
            var wrote = 0
            setContent {
                CorvidTheme(
                    darkTheme = false,
                ) { LockedPane("mail.example.test", "phone", null, onUnlock = {}, onWrite = { wrote++ }) }
            }

            onNodeWithText("Write a message").performClick()

            assertEquals(1, wrote)
        }

    @Test
    fun `the locked pane does not offer writing without a way to`() =
        runComposeUiTest {
            setContent { CorvidTheme(darkTheme = false) { LockedPane("mail.example.test", "phone", null, onUnlock = {}) } }

            onNodeWithText("Write a message").assertDoesNotExist()
        }

    @Test
    fun `the unreachable pane offers writing a message when it is given a way to`() =
        runComposeUiTest {
            var wrote = 0
            setContent {
                CorvidTheme(darkTheme = false) {
                    UnreachablePane(UiText.FromServer("no route"), onRetry = {}, onSignOut = {}, onWrite = { wrote++ })
                }
            }

            onNodeWithText("Write a message").performClick()

            assertEquals(1, wrote)
        }

    @Test
    fun `the unreachable pane does not offer writing without a way to`() =
        runComposeUiTest {
            setContent { CorvidTheme(darkTheme = false) { UnreachablePane(UiText.FromServer("no route"), onRetry = {}, onSignOut = {}) } }

            onNodeWithText("Write a message").assertDoesNotExist()
        }

    @Test
    fun `the locked pane counts the messages waiting`() =
        runComposeUiTest {
            setContent { CorvidTheme(darkTheme = false) { LockedPane("mail.example.test", "phone", null, onUnlock = {}, waiting = 3) } }

            onNodeWithText("3 messages waiting").assertExists()
        }

    @Test
    fun `one waiting message reads in the singular`() =
        runComposeUiTest {
            setContent { CorvidTheme(darkTheme = false) { LockedPane("mail.example.test", "phone", null, onUnlock = {}, waiting = 1) } }

            onNodeWithText("1 message waiting").assertExists()
        }

    @Test
    fun `no waiting line shows when nothing waits or the count is unknown`() =
        runComposeUiTest {
            setContent {
                CorvidTheme(darkTheme = false) {
                    LockedPane("mail.example.test", "phone", null, onUnlock = {}, waiting = 0)
                }
            }

            onNodeWithText("Outbox").assertDoesNotExist()
        }

    @Test
    fun `the unreachable pane counts the messages waiting`() =
        runComposeUiTest {
            setContent {
                CorvidTheme(darkTheme = false) {
                    UnreachablePane(UiText.FromServer("no route"), onRetry = {}, onSignOut = {}, waiting = 2)
                }
            }

            onNodeWithText("2 messages waiting").assertExists()
        }

    @Test
    fun `messages that need a decision are counted apart from the waiting ones`() =
        runComposeUiTest {
            setContent {
                CorvidTheme(darkTheme = false) {
                    LockedPane("mail.example.test", "phone", null, onUnlock = {}, waiting = 1, needDecision = 2)
                }
            }

            onNodeWithText("1 message waiting").assertExists()
            onNodeWithText("2 messages need a decision").assertExists()
        }
}
