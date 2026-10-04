package com.verdenroz.corvid.feature.filters

import com.verdenroz.corvid.core.data.FiltersRepository
import com.verdenroz.corvid.core.jmap.AuthLatch
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.createJmapHttpClient
import com.verdenroz.corvid.core.model.ConditionField
import com.verdenroz.corvid.core.model.ConditionOperator
import com.verdenroz.corvid.core.model.FilterAction
import com.verdenroz.corvid.core.model.FilterCondition
import com.verdenroz.corvid.core.model.FilterRule
import com.verdenroz.corvid.core.model.MatchMode
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.FakeSieveScript
import com.verdenroz.corvid.core.testing.ViewModelTestScope
import com.verdenroz.corvid.core.testing.runViewModelTest
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private const val BASE_URL = "http://127.0.0.1:9999"

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
    val client = JmapClient.connect(createJmapHttpClient(fake.engine), AuthLatch(), BASE_URL)
    return FiltersRepository(client)
}

private suspend fun ViewModelTestScope.viewModel(fake: FakeJmapServer): FiltersViewModel =
    track(FiltersViewModel(repository(fake))).also { it.ready() }

private suspend fun FiltersViewModel.ready(): FiltersUiState.Ready =
    state.first { it is FiltersUiState.Ready && it.save != SaveStatus.Saving } as FiltersUiState.Ready

class FiltersViewModelTest {
    @Test
    fun `an added rule is saved to the server and listed`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            val vm = viewModel(fake)

            vm.saveRule(null, rule("first"))

            assertEquals(listOf("first"), vm.ready().rules.map { it.name })
            assertEquals(listOf("corvid" to true), fake.sieveScripts.map { it.name to it.isActive })
        }

    @Test
    fun `a rule the server rejects stays in the editor with an error`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            fake.invalidSieve = true
            val vm = viewModel(fake)

            vm.saveRule(null, rule("first"))

            val ready = vm.ready()
            assertEquals(listOf("first"), ready.rules.map { it.name })
            assertEquals(SaveStatus.Failed("Not saved: script rejected"), ready.save)
        }

    @Test
    fun `an active foreign script is kept until the user confirms replacing it`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            fake.sieveScripts += FakeSieveScript(id = "old", name = "vacation", blobId = "b0", isActive = true)
            val vm = viewModel(fake)

            vm.saveRule(null, rule("first"))

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
            fake.sieveScripts += FakeSieveScript(id = "old", name = "vacation", blobId = "b0", isActive = true)
            val vm = viewModel(fake)
            vm.saveRule(null, rule("first"))
            vm.ready()

            vm.dismissConfirmation()

            val ready = vm.ready()
            assertEquals(SaveStatus.Failed("Not saved: another script is active"), ready.save)
            assertEquals(listOf("first"), ready.rules.map { it.name })
            assertEquals(listOf("vacation" to true), fake.sieveScripts.map { it.name to it.isActive })
        }

    @Test
    fun `an edit issued while a save is in flight is ignored`() =
        runViewModelTest {
            val vm = viewModel(FakeJmapServer())

            vm.saveRule(null, rule("first"))
            vm.saveRule(null, rule("second"))

            assertEquals(listOf("first"), vm.ready().rules.map { it.name })
        }

    @Test
    fun `moving a rule down reorders it on the server`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            val vm = viewModel(fake)
            vm.saveRule(null, rule("a"))
            vm.ready()
            vm.saveRule(null, rule("b"))
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
            vm.saveRule(null, rule("a"))
            vm.ready()
            vm.saveRule(null, rule("b"))
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
            vm.saveRule(null, rule("a"))
            vm.ready()
            vm.saveRule(null, rule("b"))
            vm.ready()

            vm.saveRule(0, rule("renamed"))

            assertEquals(listOf("renamed", "b"), vm.ready().rules.map { it.name })
        }

    @Test
    fun `an unreadable corvid script fails the screen rather than offering an empty list`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            fake.blobs["b0"] = "keep;\n".encodeToByteArray()
            fake.sieveScripts += FakeSieveScript(id = "s0", name = "corvid", blobId = "b0")

            val vm = track(FiltersViewModel(repository(fake)))

            assertIs<FiltersUiState.Failed>(vm.state.first { it !is FiltersUiState.Loading })
        }
}
