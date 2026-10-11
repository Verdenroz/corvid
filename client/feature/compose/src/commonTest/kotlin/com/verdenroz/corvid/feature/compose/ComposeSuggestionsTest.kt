package com.verdenroz.corvid.feature.compose

import androidx.lifecycle.viewModelScope
import com.verdenroz.corvid.core.data.ComposeRepository
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.model.ComposeIntent
import com.verdenroz.corvid.core.testing.FakeEmail
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.KeyedTextResolver
import com.verdenroz.corvid.core.testing.SENDABLE_ACCOUNT_EMAIL
import com.verdenroz.corvid.core.testing.ViewModelTestScope
import com.verdenroz.corvid.core.testing.client
import com.verdenroz.corvid.core.testing.runViewModelTest
import com.verdenroz.corvid.core.testing.withSendableAccount
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val OWN = SENDABLE_ACCOUNT_EMAIL

private fun server() =
    FakeJmapServer().withSendableAccount().apply {
        emails += FakeEmail("m1", "m1", setOf("inbox"), "Lunch", "alice@example.test", "2024-03-01T00:00:00Z", to = listOf(OWN))
        emails += FakeEmail("m2", "m2", setOf("sent"), "Plans", OWN, "2024-01-01T00:00:00Z", to = listOf("andy@example.test"))
        emails +=
            FakeEmail("m3", "m3", setOf("inbox"), "Book", "anna@example.test", "2024-02-01T00:00:00Z", fromName = "Anna Karenina")
        emails += FakeEmail("m4", "m4", setOf("inbox"), "Hi", "bob@example.test", "2023-01-01T00:00:00Z")
    }

class ComposeSuggestionsTest {
    private suspend fun ViewModelTestScope.viewModel(fake: FakeJmapServer = server()): ComposeViewModel {
        val client = fake.client()
        val vm = track(ComposeViewModel(ComposeRepository(client, MailboxRepository(client), KeyedTextResolver), ComposeIntent.New))
        vm.state.first { it is ComposeUiState.Editing }
        vm.idle()
        return vm
    }

    private suspend fun ComposeViewModel.idle() {
        while (true) {
            val running = viewModelScope.coroutineContext.job.children.firstOrNull() ?: return
            running.join()
        }
    }

    private fun ComposeViewModel.editing() = state.value as ComposeUiState.Editing

    private fun ComposeViewModel.suggested() = editing().suggestions?.contacts?.map { it.address.email }

    @Test
    fun `typing lists matches that start with the text first, people written to ahead of senders`() =
        runViewModelTest {
            val vm = viewModel()

            vm.editRecipients(RecipientField.To, "a")

            assertEquals(
                listOf("andy@example.test", "alice@example.test", "anna@example.test", "bob@example.test"),
                vm.suggested(),
            )
            assertEquals(RecipientField.To, vm.editing().suggestions?.field)
        }

    @Test
    fun `a display name matches as well as an address`() =
        runViewModelTest {
            val vm = viewModel()

            vm.editRecipients(RecipientField.Cc, "karen")

            assertEquals(listOf("anna@example.test"), vm.suggested())
            assertEquals(RecipientField.Cc, vm.editing().suggestions?.field)
        }

    @Test
    fun `the account's own address is never suggested`() =
        runViewModelTest {
            val vm = viewModel()

            vm.editRecipients(RecipientField.To, "verde")

            assertNull(vm.editing().suggestions)
        }

    @Test
    fun `accepting puts the named address in place of the fragment, ready for the next`() =
        runViewModelTest {
            val vm = viewModel()
            vm.editRecipients(RecipientField.To, "andy@example.test, kar")

            vm.acceptSuggestion()

            assertEquals("andy@example.test, Anna Karenina <anna@example.test>, ", vm.editing().form.to)
            assertNull(vm.editing().suggestions)
        }

    @Test
    fun `accepting with the cursor inside an earlier address replaces only that one`() =
        runViewModelTest {
            val vm = viewModel()
            vm.editRecipients(RecipientField.To, "bob@example.test, kar, x@y.test", caret = "bob@example.test, kar".length)

            vm.acceptSuggestion()

            assertEquals("bob@example.test, Anna Karenina <anna@example.test>, x@y.test", vm.editing().form.to)
        }

    @Test
    fun `an address already in the field or typed in full is not offered again`() =
        runViewModelTest {
            val vm = viewModel()

            vm.editRecipients(RecipientField.To, "andy@example.test, an")
            assertEquals(listOf("anna@example.test"), vm.suggested())

            vm.editRecipients(RecipientField.To, "anna@example.test")
            assertNull(vm.editing().suggestions)
        }

    @Test
    fun `the highlight moves with the arrows, stops at either end, and picks what Enter accepts`() =
        runViewModelTest {
            val vm = viewModel()
            vm.editRecipients(RecipientField.Bcc, "a")

            vm.moveHighlight(-1)
            assertEquals(0, vm.editing().suggestions?.highlighted)
            repeat(10) { vm.moveHighlight(1) }
            assertEquals(3, vm.editing().suggestions?.highlighted)
            vm.moveHighlight(-1)
            vm.acceptSuggestion()

            assertEquals("Anna Karenina <anna@example.test>, ", vm.editing().form.bcc)
        }

    @Test
    fun `dismissing closes the list until the next keystroke and says whether there was one`() =
        runViewModelTest {
            val vm = viewModel()
            vm.editRecipients(RecipientField.To, "al")

            assertTrue(vm.dismissSuggestions())
            assertNull(vm.editing().suggestions)
            assertFalse(vm.dismissSuggestions())

            vm.editRecipients(RecipientField.To, "ali")
            assertEquals(listOf("alice@example.test"), vm.suggested())
        }

    @Test
    fun `a separator just typed ends the fragment and the list`() =
        runViewModelTest {
            val vm = viewModel()

            vm.editRecipients(RecipientField.To, "al, ")

            assertNull(vm.editing().suggestions)
        }

    @Test
    fun `an accepted suggestion is saved as a recipient`() =
        runViewModelTest {
            val fake = server()
            val vm = viewModel(fake)
            vm.editRecipients(RecipientField.To, "kar")
            vm.acceptSuggestion()

            vm.idle()

            assertEquals(listOf("anna@example.test"), fake.emails.single { "drafts" in it.mailboxIds }.to)
        }
}
