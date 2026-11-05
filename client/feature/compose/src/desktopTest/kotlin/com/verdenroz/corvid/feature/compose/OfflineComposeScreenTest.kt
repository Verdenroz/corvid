package com.verdenroz.corvid.feature.compose

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.verdenroz.corvid.core.data.outbox.WriteOutcome
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.model.Draft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class OfflineComposeScreenTest {
    private val queued = WriteOutcome.Queued("id")

    @Test
    fun `sending queues the typed message and confirms it`() =
        runComposeUiTest {
            val written = mutableListOf<Draft>()
            val state = OfflineComposeState()
            var closed = 0
            setContent {
                CorvidTheme(darkTheme = false) {
                    OfflineComposeScreen(state, onSend = { queued.also { _ -> written += it } }, onClose = { closed++ })
                }
            }

            onNode(hasSetTextAction() and hasText("To")).performTextInput("bob@example.test")
            onNode(hasSetTextAction() and hasText("Subject")).performTextInput("hello")
            onNode(hasSetTextAction() and hasText("Message")).performTextInput("hi there")
            onNodeWithText("Send").performClick()

            onNodeWithText("Saved. It will be sent once you unlock.").assertExists()
            assertEquals(listOf("bob@example.test"), written.single().to.map { it.email })
            assertEquals("hello", written.single().subject)
            assertEquals("hi there", written.single().body)
            assertFalse(state.hasText)
            onNodeWithText("Done").performClick()
            assertEquals(1, closed)
        }

    @Test
    fun `a message with no recipient is refused and nothing is queued`() =
        runComposeUiTest {
            var writes = 0
            setContent {
                CorvidTheme(darkTheme = false) {
                    OfflineComposeScreen(OfflineComposeState(), onSend = { queued.also { writes++ } }, onClose = {})
                }
            }

            onNode(hasSetTextAction() and hasText("Message")).performTextInput("hi there")
            onNodeWithText("Send").performClick()

            onNodeWithText("Add a recipient.").assertExists()
            assertEquals(0, writes)
        }

    @Test
    fun `an empty message is refused and nothing is queued`() =
        runComposeUiTest {
            var writes = 0
            setContent {
                CorvidTheme(darkTheme = false) {
                    OfflineComposeScreen(OfflineComposeState(), onSend = { queued.also { writes++ } }, onClose = {})
                }
            }

            onNode(hasSetTextAction() and hasText("To")).performTextInput("bob@example.test")
            onNodeWithText("Send").performClick()

            onNodeWithText("Write something to send.").assertExists()
            assertEquals(0, writes)
        }

    @Test
    fun `a refused write keeps the text and says why`() =
        runComposeUiTest {
            val state = OfflineComposeState()
            setContent {
                CorvidTheme(darkTheme = false) {
                    OfflineComposeScreen(state, onSend = { WriteOutcome.HeldElsewhere }, onClose = {})
                }
            }

            onNode(hasSetTextAction() and hasText("To")).performTextInput("bob@example.test")
            onNode(hasSetTextAction() and hasText("Message")).performTextInput("hi there")
            onNodeWithText("Send").performClick()

            onNodeWithText("Not sent: Another Corvid window is using the outbox. Your text is kept.").assertExists()
            assertEquals("hi there", state.body)
        }

    @Test
    fun `closing with text typed asks before discarding it`() =
        runComposeUiTest {
            var closed = 0
            val state = OfflineComposeState()
            setContent { CorvidTheme(darkTheme = false) { OfflineComposeScreen(state, onSend = { queued }, onClose = { closed++ }) } }

            onNode(hasSetTextAction() and hasText("Message")).performTextInput("hi there")
            onNodeWithContentDescription("Close").performClick()
            assertEquals(0, closed)
            onNodeWithText("Discard this message?").assertExists()
            onNodeWithText("Discard").performClick()

            assertEquals(1, closed)
            assertFalse(state.hasText)
        }

    @Test
    fun `typed text is not in the saved state`() =
        runComposeUiTest {
            val registry = SaveableStateRegistry(restoredValues = null, canBeSaved = { true })
            val state = OfflineComposeState()
            setContent {
                CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                    CorvidTheme(darkTheme = false) { OfflineComposeScreen(state, onSend = { queued }, onClose = {}) }
                }
            }

            onNode(hasSetTextAction() and hasText("To")).performTextInput("secret-recipient@example.test")
            onNode(hasSetTextAction() and hasText("Subject")).performTextInput("secret-subject")
            onNode(hasSetTextAction() and hasText("Message")).performTextInput("secret-body")

            assertTrue(state.hasText)
            assertFalse("secret" in registry.performSave().toString())
        }
}
