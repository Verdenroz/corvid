package com.verdenroz.corvid.feature.filters

import com.verdenroz.corvid.core.model.MailboxRef
import com.verdenroz.corvid.core.model.RuleSuggestion
import com.verdenroz.corvid.core.model.SuggestionAction
import com.verdenroz.corvid.core.model.SuggestionSender
import com.verdenroz.corvid.core.model.SuggestionSenderKind
import com.verdenroz.corvid.core.testing.composedResourceEnvironment
import com.verdenroz.corvid.core.text.resolve
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FiltersTextTest {
    private val environment = composedResourceEnvironment()

    private fun suggestion(
        sender: SuggestionSenderKind,
        action: SuggestionAction,
        hits: Int = 23,
        spanDays: Int = 20,
    ) = RuleSuggestion("s1", SuggestionSender(sender, "news@x.test"), action, settled = 24, hits = hits, spanDays = spanDays)

    @Test
    fun `the rule sentence names the condition and where the mail goes`() =
        runTest {
            val folders = listOf(MailboxRef("m1", listOf("Mail", "News")))

            suspend fun sentence(suggestion: RuleSuggestion) =
                suggestion.sentence(folders).let { it.condition.resolve(environment) to it.action.resolve(environment) }

            assertEquals(
                "From is news@x.test" to "move to Archive",
                sentence(suggestion(SuggestionSenderKind.From, SuggestionAction.Archive)),
            )
            assertEquals(
                "List-Id contains <news@x.test>" to "move to Trash",
                sentence(suggestion(SuggestionSenderKind.List, SuggestionAction.Trash)),
            )
            assertEquals(
                "From is news@x.test" to "move to Mail / News",
                sentence(suggestion(SuggestionSenderKind.From, SuggestionAction.Folder("m1"))),
            )
        }

    @Test
    fun `the caption counts the messages by how they were handled and how long they span`() =
        runTest {
            val folders = listOf(MailboxRef("m1", listOf("Mail", "News")))

            suspend fun caption(suggestion: RuleSuggestion) = suggestion.caption(folders).resolve(environment)

            assertEquals(
                "Archived unread 23 times out of 24, over 3 weeks",
                caption(suggestion(SuggestionSenderKind.From, SuggestionAction.Archive)),
            )
            assertEquals(
                "Moved to Trash unread 1 time out of 24, over 5 days",
                caption(suggestion(SuggestionSenderKind.List, SuggestionAction.Trash, hits = 1, spanDays = 5)),
            )
            assertEquals(
                "Filed in Mail / News unread 23 times out of 24, over 2 weeks",
                caption(suggestion(SuggestionSenderKind.From, SuggestionAction.Folder("m1"), spanDays = 14)),
            )
            assertEquals(
                "Archived unread 23 times out of 24",
                caption(suggestion(SuggestionSenderKind.From, SuggestionAction.Archive, spanDays = 0)),
            )
        }
}
