package com.verdenroz.corvid.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.verdenroz.corvid.app.shell.BannerTone
import com.verdenroz.corvid.app.shell.OutboxBannerBar
import com.verdenroz.corvid.app.shell.tone
import com.verdenroz.corvid.core.data.outbox.OutboxAvailability
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class OutboxBannerTest {
    @Test
    fun `the banner counts the messages not sent yet`() =
        runComposeUiTest {
            setContent { CorvidTheme(darkTheme = false) { OutboxBannerBar(OutboxPending(3, needsDecision = false), {}, {}) } }

            onNodeWithText("Not sent yet: 3").assertExists()
        }

    @Test
    fun `a message waiting on the user says so in the banner`() =
        runComposeUiTest {
            setContent { CorvidTheme(darkTheme = false) { OutboxBannerBar(OutboxPending(2, needsDecision = true), {}, {}) } }

            onNodeWithText("Not sent yet: 2. Some need your decision.").assertExists()
        }

    @Test
    fun `an outbox held by another window says so`() =
        runComposeUiTest {
            setContent {
                CorvidTheme(
                    darkTheme = false,
                ) { OutboxBannerBar(OutboxPending.unavailable(OutboxAvailability.HeldElsewhere), {}, {}) }
            }

            onNodeWithText("Another Corvid window is using the outbox").assertExists()
        }

    @Test
    fun `tapping the banner opens the outbox`() =
        runComposeUiTest {
            var opened = 0
            setContent { CorvidTheme(darkTheme = false) { OutboxBannerBar(OutboxPending(1, needsDecision = false), { opened++ }, {}) } }

            onNodeWithText("Not sent yet: 1").performClick()

            assertEquals(1, opened)
        }

    @Test
    fun `the banner is calm while messages only wait on the network and urgent when one waits on the user or the outbox is held`() {
        assertEquals(BannerTone.Accent, OutboxPending(1, needsDecision = false).tone())
        assertEquals(BannerTone.Danger, OutboxPending(1, needsDecision = true).tone())
        assertEquals(BannerTone.Danger, OutboxPending.unavailable(OutboxAvailability.HeldElsewhere).tone())
    }
}
