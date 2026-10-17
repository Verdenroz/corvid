package com.verdenroz.corvid.feature.mailbox

import com.verdenroz.corvid.core.data.FiltersRepository
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.data.SuggestedRules
import com.verdenroz.corvid.core.data.SuggestionRepository
import com.verdenroz.corvid.core.model.Provenance
import com.verdenroz.corvid.core.model.RuleSuggestion
import com.verdenroz.corvid.core.model.Seal
import com.verdenroz.corvid.core.model.SenderKind
import com.verdenroz.corvid.core.model.SuggestionAction
import com.verdenroz.corvid.core.model.SuggestionSender
import com.verdenroz.corvid.core.model.SuggestionSenderKind
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.FakeSuggestion
import com.verdenroz.corvid.core.testing.KeyedTextResolver
import com.verdenroz.corvid.core.testing.client
import com.verdenroz.corvid.core.testing.withActiveScript
import com.verdenroz.corvid.core.testing.withRoleMailboxes
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private suspend fun model(fake: FakeJmapServer): SuggestionOfferModel {
    val client = fake.client()
    return SuggestionOfferModel(
        SuggestedRules(SuggestionRepository(client), FiltersRepository(client), MailboxRepository(client), KeyedTextResolver),
    )
}

private fun suggestion(
    kind: SuggestionSenderKind,
    value: String,
) = RuleSuggestion("s", SuggestionSender(kind, value), SuggestionAction.Archive, settled = 24, hits = 23)

class SuggestionOfferTest {
    @Test
    fun `a from suggestion is about the card's exact address and no lookalike`() {
        val offered = listOf(suggestion(SuggestionSenderKind.From, "news@x.test"))

        assertNotNull(offered.about("News@x.test", listId = null))
        assertNull(offered.about("lnews@x.test", listId = null))
        assertNull(offered.about("other@x.test", listId = "<news@x.test>"))
    }

    @Test
    fun `a list suggestion is about the card only when its list id matches`() {
        val offered = listOf(suggestion(SuggestionSenderKind.List, "deals.x.test"))

        assertNotNull(offered.about("a@x.test", listId = "Deals.X.test"))
        assertNull(offered.about("a@x.test", listId = "other.x.test"))
        assertNull(offered.about("a@x.test", listId = null))
    }

    @Test
    fun `a list header on unsigned or failed mail earns no list offer`() {
        val offered = listOf(suggestion(SuggestionSenderKind.List, "deals.x.test"))

        fun offer(seal: Seal) =
            offered.about(
                "a@x.test",
                Provenance(seal, null, SenderKind.Person, emptyList(), listId = "deals.x.test").trustedListId,
            )

        assertNotNull(offer(Seal.Signed("x.test")))
        assertNull(offer(Seal.Unsigned))
        assertNull(offer(Seal.Failed("x.test", null)))
    }

    @Test
    fun `accepting from the card writes the rule, tells the server and clears the offer`() =
        runTest {
            val fake = FakeJmapServer().withRoleMailboxes("inbox", "archive")
            fake.suggestions.serve(FakeSuggestion("s1"), FakeSuggestion("s2", sender = "other@x.test"))
            val model = model(fake)
            model.fetch()

            model.accept(model.state.value.suggestions.first())

            assertEquals(listOf("s1"), fake.suggestions.accepted)
            assertEquals(listOf("s2"), model.state.value.suggestions.map { it.id })
            assertEquals(false, model.state.value.working)
        }

    @Test
    fun `dismissing from the card tells the server and clears the offer`() =
        runTest {
            val fake = FakeJmapServer().withRoleMailboxes("inbox", "archive")
            fake.suggestions.serve(FakeSuggestion("s1"))
            val model = model(fake)
            model.fetch()

            model.dismiss(model.state.value.suggestions.single())

            assertEquals(listOf("s1"), fake.suggestions.dismissed)
            assertEquals(emptyList(), model.state.value.suggestions)
        }

    @Test
    fun `another client's active script asks first and the rule waits for the go-ahead`() =
        runTest {
            val fake = FakeJmapServer().withRoleMailboxes("inbox", "archive").withActiveScript()
            fake.suggestions.serve(FakeSuggestion("s1"))
            val model = model(fake)
            model.fetch()
            val suggestion = model.state.value.suggestions.single()

            model.accept(suggestion)

            assertEquals("vacation", model.state.value.confirming?.scriptName)
            assertEquals(emptyList(), fake.suggestions.accepted)

            model.accept(suggestion, replaceForeign = true)

            assertNull(model.state.value.confirming)
            assertEquals(listOf("s1"), fake.suggestions.accepted)
            assertEquals(emptyList(), model.state.value.suggestions)
        }

    @Test
    fun `declining the replacement leaves the offer and says nothing was saved`() =
        runTest {
            val fake = FakeJmapServer().withRoleMailboxes("inbox", "archive").withActiveScript()
            fake.suggestions.serve(FakeSuggestion("s1"))
            val model = model(fake)
            model.fetch()
            model.accept(model.state.value.suggestions.single())

            model.declineReplace()

            assertNull(model.state.value.confirming)
            assertNotNull(model.state.value.failed)
            assertEquals(1, model.state.value.suggestions.size)
        }

    @Test
    fun `checking reads the saved rules and drops a suggestion one of them already does`() =
        runTest {
            val fake = FakeJmapServer().withRoleMailboxes("inbox", "archive")
            fake.suggestions.serve(FakeSuggestion("s1"), FakeSuggestion("s2", sender = "other@x.test"))
            val model = model(fake)
            model.fetch()
            val (covered, open) = model.state.value.suggestions
            model.accept(covered)
            fake.suggestions.accepted.clear()

            model.check(open)

            assertEquals(open.id, model.state.value.checked)
            assertEquals(true, model.state.value.folders.any { it.id == "archive" })
        }

    @Test
    fun `a server without suggestions leaves the card without an offer`() =
        runTest {
            val model = model(FakeJmapServer().withRoleMailboxes("inbox"))

            model.fetch()

            assertEquals(emptyList(), model.state.value.suggestions)
        }
}
