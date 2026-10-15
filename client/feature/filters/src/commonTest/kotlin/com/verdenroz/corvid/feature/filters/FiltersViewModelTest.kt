package com.verdenroz.corvid.feature.filters

import androidx.compose.runtime.saveable.SaverScope
import com.verdenroz.corvid.core.data.FiltersRepository
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.data.SuggestedRules
import com.verdenroz.corvid.core.data.SuggestionRepository
import com.verdenroz.corvid.core.model.ConditionField
import com.verdenroz.corvid.core.model.ConditionOperator
import com.verdenroz.corvid.core.model.FilterAction
import com.verdenroz.corvid.core.model.FilterCondition
import com.verdenroz.corvid.core.model.FilterRule
import com.verdenroz.corvid.core.model.MatchMode
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.FakeSieveScript
import com.verdenroz.corvid.core.testing.FakeSuggestion
import com.verdenroz.corvid.core.testing.KeyedTextResolver
import com.verdenroz.corvid.core.testing.ViewModelTestScope
import com.verdenroz.corvid.core.testing.client
import com.verdenroz.corvid.core.testing.runViewModelTest
import com.verdenroz.corvid.core.testing.withActiveScript
import com.verdenroz.corvid.core.testing.withRoleMailboxes
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.feature.filters.resources.Res
import com.verdenroz.corvid.feature.filters.resources.filters_another_active
import com.verdenroz.corvid.feature.filters.resources.filters_not_saved
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private fun rule(
    name: String,
    value: String = "x",
) = FilterRule(
    name = name,
    enabled = true,
    match = MatchMode.All,
    conditions = listOf(FilterCondition(ConditionField.From, ConditionOperator.Contains, value)),
    actions = listOf(FilterAction.MarkRead),
    stop = false,
)

private suspend fun repository(fake: FakeJmapServer): FiltersRepository {
    val client = fake.client()
    return FiltersRepository(client)
}

private suspend fun suggested(fake: FakeJmapServer): SuggestedRules {
    val client = fake.client()
    return SuggestedRules(SuggestionRepository(client), FiltersRepository(client), MailboxRepository(client), KeyedTextResolver)
}

private suspend fun ViewModelTestScope.viewModel(
    fake: FakeJmapServer,
    restored: RuleDraft? = null,
): FiltersViewModel = track(FiltersViewModel(repository(fake), suggested(fake), restored)).also { it.ready() }

private fun FiltersViewModel.save(
    index: Int?,
    rule: FilterRule,
) {
    startEdit(index)
    changeDraft(rule)
    saveDraft()
}

private suspend fun FiltersViewModel.ready(): FiltersUiState.Ready =
    state.first { it is FiltersUiState.Ready && it.save != SaveStatus.Saving } as FiltersUiState.Ready

class FiltersViewModelTest {
    @Test
    fun `an added rule is saved to the server and listed`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            val vm = viewModel(fake)

            vm.save(null, rule("first"))

            assertEquals(listOf("first"), vm.ready().rules.map { it.name })
            assertEquals(listOf("corvid" to true), fake.sieveScripts.map { it.name to it.isActive })
        }

    @Test
    fun `a rule the server rejects stays in the editor with an error`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            fake.invalidSieve = true
            val vm = viewModel(fake)

            vm.save(null, rule("first"))

            val ready = vm.ready()
            assertEquals(listOf("first"), ready.rules.map { it.name })
            assertEquals(SaveStatus.Failed(uiText(Res.string.filters_not_saved, UiText.FromServer("script rejected"))), ready.save)
        }

    @Test
    fun `an active foreign script is kept until the user confirms replacing it`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            fake.withActiveScript()
            val vm = viewModel(fake)

            vm.save(null, rule("first"))

            val asked = vm.ready()
            assertEquals(SaveStatus.Confirming, asked.save)
            assertEquals("vacation", asked.foreignScript)
            assertEquals(listOf("vacation" to true), fake.sieveScripts.map { it.name to it.isActive })

            vm.confirmReplace()

            assertEquals(SaveStatus.Idle, vm.ready().save)
            assertEquals(listOf("vacation" to false, "corvid" to true), fake.sieveScripts.map { it.name to it.isActive })
        }

    @Test
    fun `declining the replacement leaves the foreign script active and the rules in the editor`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            fake.withActiveScript()
            val vm = viewModel(fake)
            vm.save(null, rule("first"))
            vm.ready()

            vm.dismissConfirmation()

            val ready = vm.ready()
            assertEquals(SaveStatus.Failed(uiText(Res.string.filters_not_saved, uiText(Res.string.filters_another_active))), ready.save)
            assertEquals(listOf("first"), ready.rules.map { it.name })
            assertEquals(listOf("vacation" to true), fake.sieveScripts.map { it.name to it.isActive })
        }

    @Test
    fun `an edit in progress survives being re-read from the ViewModel`() =
        runViewModelTest {
            val vm = viewModel(FakeJmapServer())

            vm.startEdit(null)
            vm.changeDraft(rule("typing", value = "half"))

            assertEquals(RuleDraft(null, rule("typing", value = "half")), vm.ready().editing)
        }

    @Test
    fun `starting an edit on a listed rule opens that rule and cancelling closes it`() =
        runViewModelTest {
            val vm = viewModel(FakeJmapServer())
            vm.save(null, rule("a"))
            vm.ready()

            vm.startEdit(0)
            assertEquals(RuleDraft(0, rule("a")), vm.ready().editing)

            vm.cancelEdit()
            assertEquals(null, vm.ready().editing)
        }

    @Test
    fun `back on a changed rule asks first, and a second back discards it`() =
        runViewModelTest {
            val vm = viewModel(FakeJmapServer())
            vm.startEdit(null)
            vm.changeDraft(rule("half done"))

            assertTrue(vm.back())
            assertEquals(true, vm.ready().confirmingDiscard)
            assertEquals(rule("half done"), vm.ready().editing?.rule)

            assertTrue(vm.back())
            assertEquals(null, vm.ready().editing)
            assertEquals(false, vm.ready().confirmingDiscard)
        }

    @Test
    fun `keep editing after back leaves the changed rule open`() =
        runViewModelTest {
            val vm = viewModel(FakeJmapServer())
            vm.startEdit(null)
            vm.changeDraft(rule("half done"))
            vm.back()

            vm.keepEditing()

            assertEquals(false, vm.ready().confirmingDiscard)
            assertEquals(rule("half done"), vm.ready().editing?.rule)
        }

    @Test
    fun `back on an untouched rule closes the editor without asking`() =
        runViewModelTest {
            val vm = viewModel(FakeJmapServer())
            vm.save(null, rule("a"))
            vm.ready()
            vm.startEdit(0)

            assertTrue(vm.back())

            assertEquals(null, vm.ready().editing)
            assertEquals(false, vm.ready().confirmingDiscard)
        }

    @Test
    fun `back with no rule open leaves the screen to the shell`() =
        runViewModelTest {
            assertFalse(viewModel(FakeJmapServer()).back())
        }

    @Test
    fun `saving the draft closes the editor and lists the rule`() =
        runViewModelTest {
            val vm = viewModel(FakeJmapServer())

            vm.save(null, rule("first"))

            val ready = vm.ready()
            assertEquals(null, ready.editing)
            assertEquals(listOf("first"), ready.rules.map { it.name })
        }

    @Test
    fun `saving the draft while another save is in flight keeps the editor open`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            val vm = viewModel(fake)
            val release = CompletableDeferred<Unit>()
            fake.gate = release
            vm.save(null, rule("first"))

            vm.startEdit(null)
            vm.changeDraft(rule("second"))
            vm.saveDraft()
            release.complete(Unit)

            assertEquals(RuleDraft(null, rule("second")), vm.ready().editing)
        }

    @Test
    fun `an edit issued while a save is in flight is ignored`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            val vm = viewModel(fake)
            val release = CompletableDeferred<Unit>()
            fake.gate = release

            vm.save(null, rule("first"))
            vm.save(null, rule("second"))
            release.complete(Unit)

            assertEquals(listOf("first"), vm.ready().rules.map { it.name })
        }

    @Test
    fun `moving a rule down reorders it on the server`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            val vm = viewModel(fake)
            vm.save(null, rule("a"))
            vm.ready()
            vm.save(null, rule("b"))
            vm.ready()

            vm.move(0, 1)

            assertEquals(listOf("b", "a"), vm.ready().rules.map { it.name })
            assertEquals(listOf("b", "a"), viewModel(fake).ready().rules.map { it.name })
        }

    @Test
    fun `toggling a rule off and deleting another persist`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            val vm = viewModel(fake)
            vm.save(null, rule("a"))
            vm.ready()
            vm.save(null, rule("b"))
            vm.ready()

            vm.toggle(0)
            vm.ready()
            vm.delete(1)
            vm.ready()

            val reloaded = viewModel(fake).ready()
            assertEquals(listOf("a" to false), reloaded.rules.map { it.name to it.enabled })
        }

    @Test
    fun `editing a rule replaces it in place`() =
        runViewModelTest {
            val vm = viewModel(FakeJmapServer())
            vm.save(null, rule("a"))
            vm.ready()
            vm.save(null, rule("b"))
            vm.ready()

            vm.save(0, rule("renamed"))

            assertEquals(listOf("renamed", "b"), vm.ready().rules.map { it.name })
        }

    @Test
    fun `an unreadable corvid script fails the screen rather than offering an empty list`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            fake.blobs["b0"] = "keep;\n".encodeToByteArray()
            fake.sieveScripts += FakeSieveScript(id = "s0", name = "corvid", blobId = "b0")

            val vm = track(FiltersViewModel(repository(fake), suggested(fake)))

            assertIs<FiltersUiState.Failed>(vm.state.first { it !is FiltersUiState.Loading })
        }

    @Test
    fun `a rule being edited before the screen was rebuilt is open again, unchanged`() =
        runViewModelTest {
            val draft = RuleDraft(null, rule("half done", value = "invoice"))

            val vm = viewModel(FakeJmapServer(), restored = draft)

            assertEquals(draft, vm.ready().editing)
        }

    @Test
    fun `an unsaved rule survives the round trip through saved state`() {
        val draft = RuleDraft(2, rule("half done", value = "in/voice \"q\""))

        val saved = with(RuleDraftSaver) { SaverScope { true }.save(draft) }

        assertEquals(draft, RuleDraftSaver.restore(assertNotNull(saved)))
    }

    @Test
    fun `a suggestion is listed and a server with suggestions off lists none`() =
        runViewModelTest {
            val fake = FakeJmapServer().withRoleMailboxes("archive")
            fake.suggestions.serve(FakeSuggestion("s1"))
            assertEquals(listOf("s1"), viewModel(fake).ready().suggestions.map { it.id })

            fake.suggestions.off = true

            assertEquals(emptyList(), viewModel(fake).ready().suggestions)
        }

    @Test
    fun `accepting a suggestion adds its rule and removes the card`() =
        runViewModelTest {
            val fake = FakeJmapServer().withRoleMailboxes("archive")
            fake.suggestions.serve(FakeSuggestion("s1"))
            val vm = viewModel(fake)

            vm.acceptSuggestion(vm.ready().suggestions.single())
            val ready = vm.ready()

            assertEquals(listOf("rule_name_from[news@x.test]"), ready.rules.map { it.name })
            assertEquals(emptyList(), ready.suggestions)
            assertEquals(listOf("s1"), fake.suggestions.accepted)
        }

    @Test
    fun `accepting over a foreign script waits for confirmation and then posts once`() =
        runViewModelTest {
            val fake = FakeJmapServer().withRoleMailboxes("archive").withActiveScript()
            fake.suggestions.serve(FakeSuggestion("s1"))
            val vm = viewModel(fake)

            vm.acceptSuggestion(vm.ready().suggestions.single())
            val waiting = vm.state.first { it is FiltersUiState.Ready && it.save == SaveStatus.Confirming } as FiltersUiState.Ready

            assertEquals("vacation", waiting.foreignScript)
            assertEquals(emptyList(), fake.suggestions.accepted)

            vm.confirmReplace()

            assertEquals(listOf("rule_name_from[news@x.test]"), vm.ready().rules.map { it.name })
            assertEquals(listOf("s1"), fake.suggestions.accepted)
        }

    @Test
    fun `dismissing a suggestion removes the card and tells the server`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            fake.suggestions.serve(FakeSuggestion("s1"))
            val vm = viewModel(fake)

            vm.dismissSuggestion(vm.ready().suggestions.single())

            assertEquals(emptyList(), vm.ready().suggestions)
            withContext(Dispatchers.Default) { withTimeout(5_000) { while (fake.suggestions.dismissed.isEmpty()) delay(10) } }
            assertEquals(listOf("s1"), fake.suggestions.dismissed)
        }
}
