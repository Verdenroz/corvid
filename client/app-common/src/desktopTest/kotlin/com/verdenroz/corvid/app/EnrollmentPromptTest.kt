package com.verdenroz.corvid.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.keyring.DeviceRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalTestApi::class)
class EnrollmentPromptTest {
    private val request = DeviceRequest("0123456789abcdef0123456789abcdef", "042917", Clock.System.now() + 5.minutes)

    @Test
    fun `an open request shows its id and code on desktop`() =
        runComposeUiTest {
            setContent {
                CorvidTheme(darkTheme = false) {
                    EnrollmentPrompt("laptop-1", EnrollmentRequest.Open(request), onAskAgain = {}, onApproveSelf = {}, onWatch = {})
                }
            }

            onNodeWithText(request.id).assertIsDisplayed()
            onNodeWithText(request.code).assertIsDisplayed()
        }

    @Test
    fun `an open request offers approving it with a passkey`() =
        runComposeUiTest {
            var approved = 0
            setContent {
                CorvidTheme(darkTheme = false) {
                    EnrollmentPrompt(
                        "laptop-1",
                        EnrollmentRequest.Open(request),
                        onAskAgain = {},
                        onApproveSelf = { approved++ },
                        onWatch = {},
                    )
                }
            }

            onNodeWithText("Approve with a passkey").performClick()

            assertEquals(1, approved)
        }

    @Test
    fun `an expired request offers asking again`() =
        runComposeUiTest {
            var asked = 0
            setContent {
                CorvidTheme(darkTheme = false) {
                    EnrollmentPrompt(
                        "laptop-1",
                        EnrollmentRequest.Expired,
                        onAskAgain = { asked++ },
                        onApproveSelf = {},
                        onWatch = {},
                    )
                }
            }

            onNodeWithText("Ask again").performClick()

            assertEquals(1, asked)
        }
}
