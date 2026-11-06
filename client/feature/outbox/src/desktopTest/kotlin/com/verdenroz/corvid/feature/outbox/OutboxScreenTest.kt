package com.verdenroz.corvid.feature.outbox

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.verdenroz.corvid.core.data.outbox.RejectionCategory
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock

private val NONE = OutboxActions({}, {}, {}, {}, {})

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.show(
    row: OutboxRow,
    confirming: Boolean = false,
    actions: OutboxActions = NONE,
) = setContent {
    CorvidTheme(darkTheme = false) {
        OutboxScreen(OutboxListState(listOf(row), if (confirming) row.id else null), actions, onBack = {})
    }
}

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.shows(text: String) = onNodeWithText(text).assertExists()

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.hides(text: String) = onNodeWithText(text).assertDoesNotExist()

@OptIn(ExperimentalTestApi::class)
class OutboxScreenTest {
    @Test
    fun `a waiting message shows its status, recipients and subject with no actions`() =
        runComposeUiTest {
            show(OutboxRow("1", OutboxStatus.Waiting, recipients = "bob@x.test", subject = "Lunch"))

            shows("Waiting to send")
            shows("To bob@x.test")
            shows("Lunch")
            hides("Send again")
            hides("Discard")
        }

    @Test
    fun `a sending message shows its status with no actions`() =
        runComposeUiTest {
            show(OutboxRow("1", OutboxStatus.Sending, recipients = "bob@x.test", subject = "Lunch"))

            shows("Sending")
            hides("Send again")
            hides("Discard")
        }

    @Test
    fun `a message needing a decision explains itself and offers send again and discard`() =
        runComposeUiTest {
            show(OutboxRow("1", OutboxStatus.NeedsDecision, subject = "Lunch", mayHaveBeenSent = true, canResend = true, canDiscard = true))

            shows("Needs your decision")
            shows("This message may or may not have been sent. Send it again only if it did not arrive.")
            shows("Send again")
            shows("Discard")
        }

    @Test
    fun `a rejected message names why and offers send again and discard`() =
        runComposeUiTest {
            show(OutboxRow("1", OutboxStatus.NotSent, RejectionCategory.AliasGone, subject = "Lunch", canResend = true, canDiscard = true))

            shows("Not sent")
            shows("The alias it was written from is turned off.")
            shows("Send again")
            shows("Discard")
        }

    @Test
    fun `a message that cannot be opened shows no text and offers only discard`() =
        runComposeUiTest {
            show(OutboxRow("1", OutboxStatus.CannotOpen, canDiscard = true))

            shows("Cannot be opened")
            shows("The saved copy is damaged, so its contents cannot be shown or sent.")
            hides("Send again")
            shows("Discard")
        }

    @Test
    fun `a message sealed to a previous key says so and offers only discard`() =
        runComposeUiTest {
            show(OutboxRow("1", OutboxStatus.SealedToPreviousKey, canDiscard = true))

            shows("Cannot be opened")
            shows("It was sealed to a previous key, so this device cannot open it.")
            hides("Send again")
            shows("Discard")
        }

    @Test
    fun `a recently sent message is listed with no actions`() =
        runComposeUiTest {
            show(OutboxRow("1", OutboxStatus.Sent, sentAt = Clock.System.now()))

            shows("Sent recently")
            hides("Send again")
            hides("Discard")
        }

    @Test
    fun `discarding an unconfirmed message warns that it may already have been sent`() =
        runComposeUiTest {
            show(OutboxRow("1", OutboxStatus.NeedsDecision, mayHaveBeenSent = true, canResend = true, canDiscard = true), confirming = true)

            shows("Discard this message? It may already have been sent.")
            hides("Send again")
        }

    @Test
    fun `discarding a rejected message asks without the sent warning`() =
        runComposeUiTest {
            show(OutboxRow("1", OutboxStatus.NotSent, RejectionCategory.Refused, canResend = true, canDiscard = true), confirming = true)

            shows("Discard this message?")
            hides("Discard this message? It may already have been sent.")
        }

    @Test
    fun `send again and discard act on the row they sit in`() =
        runComposeUiTest {
            val resent = mutableListOf<String>()
            val asked = mutableListOf<String>()
            show(
                OutboxRow("7", OutboxStatus.NotSent, RejectionCategory.Refused, canResend = true, canDiscard = true),
                actions = OutboxActions({}, resent::add, asked::add, {}, {}),
            )

            onNodeWithText("Send again").performClick()
            onNodeWithText("Discard").performClick()

            assertEquals(listOf("7"), resent)
            assertEquals(listOf("7"), asked)
        }

    @Test
    fun `an empty outbox says nothing is waiting`() =
        runComposeUiTest {
            setContent { CorvidTheme(darkTheme = false) { OutboxScreen(OutboxListState(), NONE, onBack = {}) } }

            shows("Nothing is waiting to be sent.")
        }
}
