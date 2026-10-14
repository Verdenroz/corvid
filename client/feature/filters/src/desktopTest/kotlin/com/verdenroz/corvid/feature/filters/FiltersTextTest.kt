package com.verdenroz.corvid.feature.filters

import com.verdenroz.corvid.core.model.MailboxRef
import com.verdenroz.corvid.core.model.RuleSuggestion
import com.verdenroz.corvid.core.model.SuggestionAction
import com.verdenroz.corvid.core.model.SuggestionActionKind
import com.verdenroz.corvid.core.model.SuggestionSender
import com.verdenroz.corvid.core.model.SuggestionSenderKind
import com.verdenroz.corvid.core.testing.composedResourceEnvironment
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.resolve
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.feature.filters.resources.Res
import com.verdenroz.corvid.feature.filters.resources.filters_replace_body
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FiltersTextTest {
    private val environment = composedResourceEnvironment()

    @Test
    fun `the replace warning keeps its quotes and apostrophe`() =
        runTest {
            assertEquals(
                "\"vacation\" is the active Sieve script. Activating Corvid's filters turns it off. It is not deleted.",
                uiText(Res.string.filters_replace_body, UiText.FromServer("vacation")).resolve(environment),
            )
        }

    private fun suggestion(
        sender: SuggestionSenderKind,
        action: SuggestionActionKind,
        mailboxId: String? = null,
    ) = RuleSuggestion("s1", SuggestionSender(sender, "news@x.test"), SuggestionAction(action, mailboxId), settled = 24, hits = 23)

    @Test
    fun `a suggestion says how many of the messages were handled and how`() =
        runTest {
            assertEquals(
                "You left 23 of 24 messages from news@x.test unread and archived them.",
                suggestion(SuggestionSenderKind.From, SuggestionActionKind.Archive).summary(emptyList())!!.resolve(environment),
            )
            assertEquals(
                "You left 23 of 24 messages from the list news@x.test unread and moved them to Trash.",
                suggestion(SuggestionSenderKind.List, SuggestionActionKind.Trash).summary(emptyList())!!.resolve(environment),
            )
            assertEquals(
                "You left 23 of 24 messages from news@x.test unread and filed them in Mail / News.",
                suggestion(SuggestionSenderKind.From, SuggestionActionKind.Folder, "m1")
                    .summary(listOf(MailboxRef("m1", listOf("Mail", "News"))))!!
                    .resolve(environment),
            )
        }
}
