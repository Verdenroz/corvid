package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.model.ConditionField
import com.verdenroz.corvid.core.model.ConditionOperator
import com.verdenroz.corvid.core.model.FilterAction
import com.verdenroz.corvid.core.model.FilterCondition
import com.verdenroz.corvid.core.model.FilterRule
import com.verdenroz.corvid.core.model.MailboxRef
import com.verdenroz.corvid.core.model.MatchMode
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.FakeMailbox
import com.verdenroz.corvid.core.testing.FakeSuggestion
import com.verdenroz.corvid.core.testing.KeyedTextResolver
import com.verdenroz.corvid.core.testing.client
import com.verdenroz.corvid.core.testing.withActiveScript
import com.verdenroz.corvid.core.testing.withRoleMailboxes
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private suspend fun suggested(fake: FakeJmapServer): Pair<SuggestedRules, FiltersRepository> {
    val client = fake.client()
    val filters = FiltersRepository(client)
    return SuggestedRules(SuggestionRepository(client), filters, MailboxRepository(client), KeyedTextResolver) to filters
}

private fun FakeJmapServer.scriptText(): String = blobs.getValue(sieveScripts.single { it.name == "corvid" }.blobId).decodeToString()

private fun fromRule(
    sender: String,
    action: FilterAction,
) = FilterRule(
    name = "mine",
    enabled = true,
    match = MatchMode.All,
    conditions = listOf(FilterCondition(ConditionField.From, ConditionOperator.Is, sender)),
    actions = listOf(action),
    stop = false,
)

class SuggestedRulesTest {
    @Test
    fun `accepting a from suggestion saves a demoting rule and tells the server`() =
        runTest {
            val fake = FakeJmapServer().withRoleMailboxes("inbox", "archive")
            fake.suggestions.serve(listOf(FakeSuggestion("s1")))
            val (rules, filters) = suggested(fake)
            val suggestion = rules.pending(filters.load()).single()

            val result = rules.accept(suggestion, replaceForeign = false)

            assertEquals(SaveResult.Saved, result)
            assertEquals(
                FilterRule(
                    "rule_name_from[news@x.test]",
                    true,
                    MatchMode.All,
                    listOf(FilterCondition(ConditionField.From, ConditionOperator.Is, "news@x.test")),
                    listOf(FilterAction.MoveToFolder(MailboxRef("archive", listOf("Archive")))),
                    stop = false,
                ),
                filters.load().rules.single(),
            )
            assertEquals(listOf("s1"), fake.suggestions.accepted)
        }

    @Test
    fun `accepting a list suggestion compiles to a list-id contains with angle brackets`() =
        runTest {
            val fake = FakeJmapServer().withRoleMailboxes("trash")
            fake.suggestions.serve(listOf(FakeSuggestion("s1", senderKind = "list", sender = "news.x.test", actionKind = "trash")))
            val (rules, filters) = suggested(fake)

            rules.accept(rules.pending(filters.load()).single(), replaceForeign = false)

            assertEquals("rule_name_list[news.x.test]", filters.load().rules.single().name)
            assertTrue("header :contains \"list-id\" \"<news.x.test>\"" in fake.scriptText())
        }

    @Test
    fun `accepting an archive suggestion makes the archive mailbox when there is none`() =
        runTest {
            val fake = FakeJmapServer().withRoleMailboxes("inbox")
            fake.suggestions.serve(listOf(FakeSuggestion("s1")))
            val (rules, filters) = suggested(fake)

            val result = rules.accept(rules.pending(filters.load()).single(), replaceForeign = false)

            assertEquals(SaveResult.Saved, result)
            assertTrue(fake.mailboxes.any { it.role == "archive" })
        }

    @Test
    fun `accepting a folder suggestion files into that folder`() =
        runTest {
            val fake = FakeJmapServer()
            fake.mailboxes += FakeMailbox(id = "m1", name = "News")
            fake.suggestions.serve(listOf(FakeSuggestion("s1", actionKind = "folder", mailboxId = "m1")))
            val (rules, filters) = suggested(fake)

            rules.accept(rules.pending(filters.load()).single(), replaceForeign = false)

            assertEquals(
                listOf<FilterAction>(FilterAction.MoveToFolder(MailboxRef("m1", listOf("News")))),
                filters.load().rules.single().actions,
            )
        }

    @Test
    fun `a server with suggestions off shows none`() =
        runTest {
            val fake = FakeJmapServer()
            fake.suggestions.serve(listOf(FakeSuggestion("s1")))
            fake.suggestions.off = true
            val (rules, filters) = suggested(fake)

            assertEquals(emptyList(), rules.pending(filters.load()))
        }

    @Test
    fun `a suggestion a rule already carries out is hidden`() =
        runTest {
            val fake = FakeJmapServer().withRoleMailboxes("archive")
            fake.suggestions.serve(listOf(FakeSuggestion("s1"), FakeSuggestion("s2", sender = "other@x.test")))
            val (rules, filters) = suggested(fake)
            filters.save(
                listOf(fromRule("news@x.test", FilterAction.MoveToFolder(MailboxRef("archive", listOf("Archive"))))),
                replaceForeign = false,
            )

            assertEquals(listOf("s2"), rules.pending(filters.load()).map { it.id })
        }

    @Test
    fun `a rule that files the sender elsewhere does not hide the suggestion`() =
        runTest {
            val fake = FakeJmapServer().withRoleMailboxes("archive", "trash")
            fake.suggestions.serve(listOf(FakeSuggestion("s1")))
            val (rules, filters) = suggested(fake)
            filters.save(listOf(fromRule("news@x.test", FilterAction.MoveToTrash)), replaceForeign = false)

            assertEquals(listOf("s1"), rules.pending(filters.load()).map { it.id })
        }

    @Test
    fun `a suggestion for a folder that is gone is hidden`() =
        runTest {
            val fake = FakeJmapServer()
            fake.suggestions.serve(listOf(FakeSuggestion("s1", actionKind = "folder", mailboxId = "gone")))
            val (rules, filters) = suggested(fake)

            assertEquals(emptyList(), rules.pending(filters.load()))
        }

    @Test
    fun `accepting over a foreign script posts nothing until the replacement is confirmed`() =
        runTest {
            val fake = FakeJmapServer().withRoleMailboxes("archive").withActiveScript()
            fake.suggestions.serve(listOf(FakeSuggestion("s1")))
            val (rules, filters) = suggested(fake)
            val suggestion = rules.pending(filters.load()).single()

            val first = rules.accept(suggestion, replaceForeign = false)

            assertEquals(SaveResult.NeedsConfirmation("vacation"), first)
            assertEquals(emptyList(), fake.suggestions.accepted)

            val second = rules.accept(suggestion, replaceForeign = true)

            assertEquals(SaveResult.Saved, second)
            assertEquals(listOf("s1"), fake.suggestions.accepted)
        }

    @Test
    fun `a rejected save posts nothing`() =
        runTest {
            val fake = FakeJmapServer().withRoleMailboxes("archive")
            fake.invalidSieve = true
            fake.suggestions.serve(listOf(FakeSuggestion("s1")))
            val (rules, filters) = suggested(fake)

            val result = rules.accept(rules.pending(filters.load()).single(), replaceForeign = false)

            assertIs<SaveResult.Rejected>(result)
            assertEquals(emptyList(), fake.suggestions.accepted)
        }

    @Test
    fun `dismissing tells the server`() =
        runTest {
            val fake = FakeJmapServer()
            fake.suggestions.serve(listOf(FakeSuggestion("s1")))
            val (rules, filters) = suggested(fake)

            rules.dismiss(rules.pending(filters.load()).single())

            assertEquals(listOf("s1"), fake.suggestions.dismissed)
        }

    @Test
    fun `a disabled rule or a narrower one does not hide the suggestion`() =
        runTest {
            val fake = FakeJmapServer().withRoleMailboxes("archive")
            fake.suggestions.serve(listOf(FakeSuggestion("s1")))
            val (rules, filters) = suggested(fake)
            val archive = FilterAction.MoveToFolder(MailboxRef("archive", listOf("Archive")))
            val narrower =
                fromRule("news@x.test", archive).let {
                    it.copy(conditions = it.conditions + FilterCondition(ConditionField.Subject, ConditionOperator.Contains, "sale"))
                }
            filters.save(listOf(fromRule("news@x.test", archive).copy(enabled = false), narrower), replaceForeign = false)

            assertEquals(listOf("s1"), rules.pending(filters.load()).map { it.id })
        }
}
