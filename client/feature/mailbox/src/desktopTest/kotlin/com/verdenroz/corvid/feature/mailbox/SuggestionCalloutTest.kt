package com.verdenroz.corvid.feature.mailbox

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.model.RuleSuggestion
import com.verdenroz.corvid.core.model.SuggestionAction
import com.verdenroz.corvid.core.model.SuggestionSender
import com.verdenroz.corvid.core.model.SuggestionSenderKind
import kotlin.test.Test
import kotlin.test.assertEquals

private val ARCHIVE =
    RuleSuggestion(
        "s1",
        SuggestionSender(SuggestionSenderKind.From, "news@x.test"),
        SuggestionAction.Archive,
        settled = 24,
        hits = 23,
        spanDays = 20,
    )

@OptIn(ExperimentalTestApi::class)
class SuggestionCalloutTest {
    private val taken = mutableListOf<String>()
    private val actions =
        OfferActions(
            accept = { taken += "accept ${it.id}" },
            dismiss = { taken += "dismiss ${it.id}" },
        )

    @Test
    fun `the callout names the habit and offers to act on it or not now`() =
        runDesktopComposeUiTest {
            setContent {
                CorvidTheme(darkTheme = false) {
                    SuggestionCallout(
                        ARCHIVE,
                        SuggestionOfferState(suggestions = listOf(ARCHIVE)),
                        wide = false,
                        actions = actions,
                    )
                }
            }

            onNodeWithText("You archive these unread. 23 of the last 24, over 3 weeks.").assertExists()
            onNodeWithText("Not now").performClick()
            onNodeWithText("Archive them on arrival").performClick()

            assertEquals(listOf("dismiss s1", "accept s1"), taken)
        }
}
