package com.verdenroz.corvid.feature.mailbox

import com.verdenroz.corvid.core.model.MailboxRef
import com.verdenroz.corvid.core.model.RuleSuggestion
import com.verdenroz.corvid.core.model.SuggestionAction
import com.verdenroz.corvid.core.model.SuggestionSender
import com.verdenroz.corvid.core.model.SuggestionSenderKind
import com.verdenroz.corvid.core.testing.composedResourceEnvironment
import com.verdenroz.corvid.core.text.resolve
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.custody_summarised
import com.verdenroz.corvid.feature.mailbox.resources.custody_summarised_by
import com.verdenroz.corvid.feature.mailbox.resources.mailbox_from
import com.verdenroz.corvid.feature.mailbox.resources.summary_caveat
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MailboxTextTest {
    private val environment = composedResourceEnvironment()

    @Test
    fun `the sender line resolves with the address filled in`() =
        runTest {
            assertEquals("From: ada@example.test", uiText(Res.string.mailbox_from, "ada@example.test").resolve(environment))
        }

    @Test
    fun `the overnight line names up to three people and counts the lists`() =
        runTest {
            suspend fun line(
                people: List<String>,
                lists: Int,
            ) = OvernightEntry("d1", people.size + lists, people, lists).line.resolve(environment)

            assertEquals("Marta, Ana and Jonas wrote; 9 from lists", line(listOf("Marta", "Ana", "Jonas", "Kim"), 9))
            assertEquals("Marta and Ana wrote; 1 from a list", line(listOf("Marta", "Ana"), 1))
            assertEquals("Marta wrote", line(listOf("Marta"), 0))
            assertEquals("3 from lists", line(emptyList(), 3))
        }

    @Test
    fun `the offer says what the sender's mail gets, how often and what accepting does`() =
        runTest {
            val folders = listOf(MailboxRef("m1", listOf("Mail", "News")))

            fun offered(
                action: SuggestionAction,
                spanDays: Int = 20,
            ) = RuleSuggestion(
                "s",
                SuggestionSender(SuggestionSenderKind.From, "a@x.test"),
                action,
                settled = 24,
                hits = 23,
                spanDays = spanDays,
            )

            val archive = offered(SuggestionAction.Archive)
            assertEquals("You archive these unread.", archive.lead(folders).resolve(environment))
            assertEquals("23 of the last 24, over 3 weeks.", archive.detail().resolve(environment))
            assertEquals("23 of the last 24.", offered(SuggestionAction.Archive, spanDays = 0).detail().resolve(environment))
            assertEquals("Archive them on arrival", archive.accepts(folders).resolve(environment))
            assertEquals("Trash them on arrival", offered(SuggestionAction.Trash).accepts(folders).resolve(environment))
            val filed = offered(SuggestionAction.Folder("m1"))
            assertEquals("You file these in Mail / News unread.", filed.lead(folders).resolve(environment))
            assertEquals("File them in Mail / News on arrival", filed.accepts(folders).resolve(environment))
        }

    @Test
    fun `the summary says it is written from the thread and can be wrong`() =
        runTest {
            assertEquals(
                "Written from the messages below; it can be wrong or misled by them.",
                uiText(Res.string.summary_caveat).resolve(environment),
            )
        }

    @Test
    fun `the summarised cell names the model and the time`() =
        runTest {
            assertEquals("Summarised", uiText(Res.string.custody_summarised).resolve(environment))
            assertEquals("qwen3 · Sun 07:02", uiText(Res.string.custody_summarised_by, "qwen3", "Sun 07:02").resolve(environment))
        }
}
