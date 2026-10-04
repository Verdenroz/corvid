package com.verdenroz.corvid.feature.compose

import com.verdenroz.corvid.core.data.ComposeRepository
import com.verdenroz.corvid.core.jmap.AuthLatch
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.createJmapHttpClient
import com.verdenroz.corvid.core.model.ComposeIntent
import com.verdenroz.corvid.core.model.EmailAddress
import com.verdenroz.corvid.core.model.EmailDetail
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.SENDABLE_ACCOUNT_EMAIL
import com.verdenroz.corvid.core.testing.ViewModelTestScope
import com.verdenroz.corvid.core.testing.runViewModelTest
import com.verdenroz.corvid.core.testing.withSendableAccount
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

private const val BASE_URL = "http://127.0.0.1:9999"
private const val OWN = SENDABLE_ACCOUNT_EMAIL

private val ORIGINAL =
    EmailDetail(
        id = "orig",
        subject = "Lunch",
        from = EmailAddress(null, "alice@example.test"),
        to = listOf(EmailAddress(null, OWN), EmailAddress(null, "bob@example.test")),
        cc = emptyList(),
        replyTo = emptyList(),
        receivedAt = Instant.parse("2024-03-01T12:00:00Z"),
        messageId = listOf("m1@example.test"),
        references = emptyList(),
        body = "see you at noon",
        bodyTruncated = false,
    )

class ComposeViewModelTest {
    private fun server() = FakeJmapServer().withSendableAccount()

    private suspend fun ViewModelTestScope.viewModel(
        fake: FakeJmapServer,
        intent: ComposeIntent = ComposeIntent.New,
    ): ComposeViewModel {
        val client = JmapClient.connect(createJmapHttpClient(fake.engine), AuthLatch(), BASE_URL)
        return track(ComposeViewModel(ComposeRepository(client), intent))
    }

    private suspend fun ComposeViewModel.editing() = state.first { it is ComposeUiState.Editing } as ComposeUiState.Editing

    private suspend fun ComposeViewModel.settled() = state.first { it !is ComposeUiState.Editing || !it.sending }

    @Test
    fun `an account with no identity says so instead of opening the form`() =
        runViewModelTest {
            val fake = server()
            fake.identities.clear()
            val vm = viewModel(fake)

            val failed = assertIs<ComposeUiState.Failed>(vm.state.first { it !is ComposeUiState.Loading })

            assertEquals("This account has no sending identity.", failed.message)
        }

    @Test
    fun `sending a new message files it under sent and submits it`() =
        runViewModelTest {
            val fake = server()
            val vm = viewModel(fake)
            vm.editing()
            vm.edit { copy(to = "dan@example.test, eve@example.test", subject = "Hi", body = "hello") }

            vm.send()

            assertEquals(ComposeUiState.Sent, vm.settled())
            val email = fake.emails.single { it.id == fake.submissions.single().emailId }
            assertEquals(setOf("sent"), email.mailboxIds)
            assertEquals(listOf("dan@example.test", "eve@example.test"), email.to)
        }

    @Test
    fun `a reply opens prefilled with the sender, subject and quoted body`() =
        runViewModelTest {
            val vm = viewModel(server(), ComposeIntent.Reply(ORIGINAL, all = false))

            val form = vm.editing().form

            assertEquals("alice@example.test", form.to)
            assertEquals("Re: Lunch", form.subject)
            assertTrue(form.body.endsWith("wrote:\n> see you at noon"))
        }

    @Test
    fun `a sent reply carries the original's message id as in-reply-to and references`() =
        runViewModelTest {
            val fake = server()
            val vm = viewModel(fake, ComposeIntent.Reply(ORIGINAL, all = false))
            vm.editing()

            vm.send()

            assertEquals(ComposeUiState.Sent, vm.settled())
            val email = fake.emails.single { it.id == fake.submissions.single().emailId }
            assertEquals(listOf("m1@example.test"), email.inReplyTo)
            assertEquals(listOf("m1@example.test"), email.references)
        }

    @Test
    fun `replying to a sender whose name contains a comma sends to one address`() =
        runViewModelTest {
            val fake = server()
            val original = ORIGINAL.copy(from = EmailAddress("Doe, Jane", "jane@example.test"))
            val vm = viewModel(fake, ComposeIntent.Reply(original, all = false))
            assertEquals("\"Doe, Jane\" <jane@example.test>", vm.editing().form.to)

            vm.send()

            vm.settled()
            val email = fake.emails.single { it.id == fake.submissions.single().emailId }
            assertEquals(listOf("jane@example.test"), email.to)
        }

    @Test
    fun `reply all leaves the account's own address out of cc`() =
        runViewModelTest {
            val vm = viewModel(server(), ComposeIntent.Reply(ORIGINAL, all = true))

            assertEquals("bob@example.test", vm.editing().form.cc)
        }

    @Test
    fun `a forward opens with no recipient and the original below a header block`() =
        runViewModelTest {
            val vm = viewModel(server(), ComposeIntent.Forward(ORIGINAL))

            val form = vm.editing().form

            assertEquals("", form.to)
            assertEquals("Fwd: Lunch", form.subject)
            assertTrue(form.body.contains("---------- Forwarded message ----------"))
        }

    @Test
    fun `a failed submission keeps the text and shows an error`() =
        runViewModelTest {
            val fake = server()
            fake.refuseSubmission = true
            val vm = viewModel(fake)
            vm.editing()
            vm.edit { copy(to = "dan@example.test", subject = "Hi", body = "hello") }
            val typed = vm.editing().form

            vm.send()

            val editing = assertIs<ComposeUiState.Editing>(vm.settled())
            assertEquals(typed, editing.form)
            assertEquals(false, editing.sending)
            assertNotNull(editing.error)
            assertTrue(fake.submissions.isEmpty())
        }

    @Test
    fun `sending with no recipient asks for one without calling the server`() =
        runViewModelTest {
            val fake = server()
            val vm = viewModel(fake)
            vm.editing()
            val requestsBefore = fake.requestCount

            vm.send()

            assertEquals("Add a recipient.", assertIs<ComposeUiState.Editing>(vm.state.value).error)
            assertEquals(requestsBefore, fake.requestCount)
        }
}
