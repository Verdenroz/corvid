package com.verdenroz.corvid.feature.compose

import com.verdenroz.corvid.core.data.ComposeRepository
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.jmap.AuthLatch
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.createJmapHttpClient
import com.verdenroz.corvid.core.model.ComposeIntent
import com.verdenroz.corvid.core.testing.FakeEmail
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.KeyedTextResolver
import com.verdenroz.corvid.core.testing.SENDABLE_ACCOUNT_EMAIL
import com.verdenroz.corvid.core.testing.ViewModelTestScope
import com.verdenroz.corvid.core.testing.runViewModelTest
import com.verdenroz.corvid.core.testing.withSendableAccount
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.feature.compose.resources.Res
import com.verdenroz.corvid.feature.compose.resources.compose_add_recipient
import com.verdenroz.corvid.feature.compose.resources.compose_no_identity
import com.verdenroz.corvid.feature.compose.resources.compose_not_sent
import com.verdenroz.corvid.feature.compose.resources.compose_original_gone
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val BASE_URL = "http://127.0.0.1:9999"
private const val OWN = SENDABLE_ACCOUNT_EMAIL

private val ORIGINAL =
    FakeEmail(
        id = "orig",
        threadId = "t1",
        mailboxIds = setOf("inbox"),
        subject = "Lunch",
        from = "alice@example.test",
        receivedAt = "2024-03-01T12:00:00Z",
        to = listOf(OWN, "bob@example.test"),
        body = "see you at noon",
        messageId = listOf("m1@example.test"),
    )

class ComposeViewModelTest {
    private fun server(original: FakeEmail = ORIGINAL) = FakeJmapServer().withSendableAccount().also { it.emails += original }

    private suspend fun ViewModelTestScope.viewModel(
        fake: FakeJmapServer,
        intent: ComposeIntent = ComposeIntent.New,
        restored: ComposeForm? = null,
    ): ComposeViewModel {
        val client = JmapClient.connect(createJmapHttpClient(fake.engine), AuthLatch(), BASE_URL)
        return track(ComposeViewModel(ComposeRepository(client, MailboxRepository(client), KeyedTextResolver), intent, restored))
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

            assertEquals(uiText(Res.string.compose_no_identity), failed.message)
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
            val vm = viewModel(server(), ComposeIntent.Reply("orig", all = false))

            val form = vm.editing().form

            assertEquals("alice@example.test", form.to)
            assertEquals("Re: Lunch", form.subject)
            assertTrue(form.body.endsWith("alice@example.test]\n> see you at noon"))
        }

    @Test
    fun `a sent reply carries the original's message id as in-reply-to and references`() =
        runViewModelTest {
            val fake = server()
            val vm = viewModel(fake, ComposeIntent.Reply("orig", all = false))
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
            val fake = server(ORIGINAL.copy(from = "jane@example.test", fromName = "Doe, Jane"))
            val vm = viewModel(fake, ComposeIntent.Reply("orig", all = false))
            assertEquals("\"Doe, Jane\" <jane@example.test>", vm.editing().form.to)

            vm.send()

            vm.settled()
            val email = fake.emails.single { it.id == fake.submissions.single().emailId }
            assertEquals(listOf("jane@example.test"), email.to)
        }

    @Test
    fun `reply all leaves the account's own address out of cc`() =
        runViewModelTest {
            val vm = viewModel(server(), ComposeIntent.Reply("orig", all = true))

            assertEquals("bob@example.test", vm.editing().form.cc)
        }

    @Test
    fun `a forward opens with no recipient and the original below a header block`() =
        runViewModelTest {
            val vm = viewModel(server(), ComposeIntent.Forward("orig"))

            val form = vm.editing().form

            assertEquals("", form.to)
            assertEquals("Fwd: Lunch", form.subject)
            assertTrue(form.body.startsWith("\n\nforwarded_message\n"))
        }

    @Test
    fun `a reply to a message deleted before compose opened says so instead of opening the form`() =
        runViewModelTest {
            val fake = server()
            fake.emails.clear()
            val vm = viewModel(fake, ComposeIntent.Reply("orig", all = false))

            val failed = assertIs<ComposeUiState.Failed>(vm.state.first { it !is ComposeUiState.Loading })

            assertEquals(uiText(Res.string.compose_original_gone), failed.message)
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
            assertEquals(uiText(Res.string.compose_not_sent, UiText.FromServer("submission refused")), editing.error)
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

            assertEquals(uiText(Res.string.compose_add_recipient), assertIs<ComposeUiState.Editing>(vm.state.value).error)
            assertEquals(requestsBefore, fake.requestCount)
        }

    @Test
    fun `back on an untouched form closes straight away`() =
        runViewModelTest {
            val vm = viewModel(server())
            vm.editing()

            assertEquals(true, vm.requestClose())
        }

    @Test
    fun `back on an edited form asks first, and a second back discards`() =
        runViewModelTest {
            val vm = viewModel(server())
            vm.editing()
            vm.edit { copy(body = "half a thought") }

            assertEquals(false, vm.requestClose())
            assertEquals(true, vm.editing().confirmingDiscard)
            assertEquals(true, vm.requestClose())
        }

    @Test
    fun `keep editing dismisses the question and leaves the text`() =
        runViewModelTest {
            val vm = viewModel(server())
            vm.editing()
            vm.edit { copy(body = "half a thought") }
            vm.requestClose()

            vm.keepEditing()

            assertEquals(false, vm.editing().confirmingDiscard)
            assertEquals("half a thought", vm.editing().form.body)
            assertEquals(false, vm.requestClose())
        }

    @Test
    fun `a reply untouched since it opened closes without asking`() =
        runViewModelTest {
            val vm = viewModel(server(), ComposeIntent.Reply("orig", all = false))
            vm.editing()

            assertEquals(true, vm.requestClose())
        }

    @Test
    fun `text typed before the screen was rebuilt comes back, and still counts as unsent`() =
        runViewModelTest {
            val typed = ComposeForm(to = "ada@x.test", cc = "", bcc = "", subject = "", body = "half a thought")
            val vm = viewModel(server(), ComposeIntent.Reply("orig", all = false), restored = typed)

            assertEquals(typed, vm.editing().form)
            assertEquals(false, vm.requestClose())
        }
}
