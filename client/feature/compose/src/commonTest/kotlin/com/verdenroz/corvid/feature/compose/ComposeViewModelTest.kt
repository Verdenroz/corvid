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
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.feature.compose.resources.Res
import com.verdenroz.corvid.feature.compose.resources.compose_add_recipient
import com.verdenroz.corvid.feature.compose.resources.compose_draft_gone
import com.verdenroz.corvid.feature.compose.resources.compose_no_identity
import com.verdenroz.corvid.feature.compose.resources.compose_not_sent
import com.verdenroz.corvid.feature.compose.resources.compose_original_gone
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

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
        restored: ComposeRestore? = null,
    ): ComposeViewModel {
        val client = fake.client()
        return track(ComposeViewModel(ComposeRepository(client, MailboxRepository(client), KeyedTextResolver), intent, restored))
    }

    private suspend fun ComposeViewModel.editing() = state.first { it is ComposeUiState.Editing } as ComposeUiState.Editing

    private suspend fun ComposeViewModel.idle() {
        while (true) {
            val running = viewModelScope.coroutineContext.job.children.firstOrNull() ?: return
            running.join()
        }
    }

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
    fun `back on a form the server refused asks first, and a second back discards`() =
        runViewModelTest {
            val fake = server()
            val vm = viewModel(fake)
            vm.editing()
            fake.unreachable = true
            vm.edit { copy(body = "half a thought") }
            vm.idle()

            assertEquals(false, vm.requestClose())
            assertEquals(true, vm.editing().confirmingDiscard)
            assertEquals(true, vm.requestClose())
        }

    @Test
    fun `keep editing dismisses the question and leaves the text`() =
        runViewModelTest {
            val fake = server()
            val vm = viewModel(fake)
            vm.editing()
            fake.unreachable = true
            vm.edit { copy(body = "half a thought") }
            vm.idle()
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
    fun `text typed before the screen was rebuilt comes back and is saved`() =
        runViewModelTest {
            val fake = server()
            val typed = ComposeForm(to = "ada@x.test", cc = "", bcc = "", subject = "", body = "half a thought")
            val vm = viewModel(fake, ComposeIntent.Reply("orig", all = false), restored = ComposeRestore(typed, draftId = null))

            assertEquals(typed, vm.editing().form)
            vm.idle()

            assertEquals(listOf("half a thought"), drafts(fake).map { it.body })
        }

    @Test
    fun `a rebuilt screen keeps replacing the draft it had saved`() =
        runViewModelTest {
            val fake = server()
            val first = viewModel(fake)
            first.editing()
            first.edit { copy(to = "dan@example.test", body = "one") }
            first.idle()
            val saved = first.editing()
            val vm = viewModel(fake, restored = ComposeRestore(saved.form.copy(body = "two"), saved.draftId))

            vm.editing()
            vm.idle()

            assertEquals(listOf("two"), drafts(fake).map { it.body })
        }

    @Test
    fun `restored text whose draft is gone saves as a new draft instead of failing`() =
        runViewModelTest {
            val fake = server()
            val typed = ComposeForm(to = "ada@x.test", cc = "", bcc = "", subject = "", body = "half a thought")
            val vm = viewModel(fake, ComposeIntent.Draft("replaced"), restored = ComposeRestore(typed, draftId = "replaced"))

            val editing = vm.editing()
            assertEquals(typed, editing.form)
            assertEquals(null, editing.draftId)
            vm.idle()

            assertEquals(listOf("half a thought"), drafts(fake).map { it.body })
        }

    @Test
    fun `a restored reply whose draft is gone keeps its thread headers`() =
        runViewModelTest {
            val fake = server()
            val typed = ComposeForm(to = "alice@example.test", cc = "", bcc = "", subject = "Re: Lunch", body = "on my way")
            val vm = viewModel(fake, ComposeIntent.Reply("orig", all = false), restored = ComposeRestore(typed, draftId = "replaced"))

            vm.editing()
            vm.idle()

            assertEquals(listOf("m1@example.test"), drafts(fake).single().inReplyTo)
        }

    @Test
    fun `typing then pausing saves one server draft`() =
        runViewModelTest {
            val fake = server()
            val vm = viewModel(fake)
            vm.editing()

            vm.edit { copy(to = "dan@example.test", subject = "Hi", body = "hello") }
            delay(1.seconds)
            assertEquals(emptyList(), drafts(fake))
            vm.idle()

            val draft = drafts(fake).single()
            assertEquals(listOf("dan@example.test"), draft.to)
            assertEquals("Hi", draft.subject)
            assertEquals("hello", draft.body)
            assertEquals(draft.id, vm.editing().draftId)
        }

    @Test
    fun `typing again restarts the pause`() =
        runViewModelTest {
            val fake = server()
            val vm = viewModel(fake)
            vm.editing()

            vm.edit { copy(body = "a") }
            delay(1.5.seconds)
            vm.edit { copy(body = "ab") }
            delay(1.5.seconds)
            assertEquals(emptyList(), drafts(fake))
            delay(1.seconds)
            vm.idle()

            assertEquals(listOf("ab"), drafts(fake).map { it.body })
        }

    @Test
    fun `each save replaces the one before it`() =
        runViewModelTest {
            val fake = server()
            val vm = viewModel(fake)
            vm.editing()
            vm.edit { copy(body = "one") }
            vm.idle()

            vm.edit { copy(body = "two") }
            vm.idle()

            assertEquals(listOf("two"), drafts(fake).map { it.body })
        }

    @Test
    fun `an empty compose saves nothing`() =
        runViewModelTest {
            val fake = server()
            val vm = viewModel(fake)
            vm.editing()

            vm.edit { copy(body = "x") }
            vm.edit { copy(body = " ") }
            vm.idle()

            assertEquals(emptyList(), drafts(fake))
        }

    @Test
    fun `emptying a saved compose deletes its draft`() =
        runViewModelTest {
            val fake = server()
            val vm = viewModel(fake)
            vm.editing()
            vm.edit { copy(body = "x") }
            vm.idle()

            vm.edit { copy(body = "") }
            vm.idle()

            assertEquals(emptyList(), drafts(fake))
            assertEquals(null, vm.editing().draftId)
        }

    @Test
    fun `a reply nobody has touched saves nothing`() =
        runViewModelTest {
            val fake = server()
            val vm = viewModel(fake, ComposeIntent.Reply("orig", all = false))
            vm.editing()
            vm.idle()

            assertEquals(emptyList(), drafts(fake))
        }

    @Test
    fun `a saved reply keeps its thread headers`() =
        runViewModelTest {
            val fake = server()
            val vm = viewModel(fake, ComposeIntent.Reply("orig", all = false))
            vm.editing()

            vm.edit { copy(body = "on my way\n" + body) }
            vm.idle()

            val draft = drafts(fake).single()
            assertEquals(listOf("m1@example.test"), draft.inReplyTo)
            assertEquals(listOf("m1@example.test"), draft.references)
        }

    @Test
    fun `a failed save says so, keeps the text and retries on demand`() =
        runViewModelTest {
            val fake = server()
            val vm = viewModel(fake)
            vm.editing()
            fake.unreachable = true

            vm.edit { copy(body = "hello") }
            vm.idle()

            assertEquals(true, vm.editing().saveFailed)
            assertEquals("hello", vm.editing().form.body)
            fake.unreachable = false
            vm.retrySave()
            vm.idle()

            assertEquals(false, vm.editing().saveFailed)
            assertEquals(listOf("hello"), drafts(fake).map { it.body })
        }

    @Test
    fun `back on a saved draft closes without asking and keeps it`() =
        runViewModelTest {
            val fake = server()
            val vm = viewModel(fake)
            vm.editing()
            vm.edit { copy(body = "hello") }
            vm.idle()

            assertEquals(true, vm.requestClose())
            assertEquals(listOf("hello"), drafts(fake).map { it.body })
        }

    @Test
    fun `releasing the screen saves what was typed inside the pause`() =
        runViewModelTest {
            val fake = server()
            val vm = viewModel(fake)
            vm.editing()
            vm.edit { copy(body = "hello") }

            assertEquals(true, vm.requestClose())
            vm.release()
            vm.idle()

            assertEquals(listOf("hello"), drafts(fake).map { it.body })
        }

    @Test
    fun `discarding after a refused save deletes the draft the server still holds`() =
        runViewModelTest {
            val fake = server()
            val vm = viewModel(fake)
            vm.editing()
            vm.edit { copy(body = "one") }
            vm.idle()
            fake.unreachable = true
            vm.edit { copy(body = "two") }
            vm.idle()
            fake.unreachable = false

            assertEquals(false, vm.requestClose())
            assertEquals(true, vm.requestClose())
            vm.release()
            vm.idle()

            assertEquals(emptyList(), drafts(fake))
        }

    @Test
    fun `a draft reopens prefilled and sending it leaves nothing in drafts`() =
        runViewModelTest {
            val fake = server()
            fake.emails += draftEmail()
            val vm = viewModel(fake, ComposeIntent.Draft("d1"))

            val form = vm.editing().form
            assertEquals("dan@example.test", form.to)
            assertEquals("eve@example.test", form.cc)
            assertEquals("hidden@example.test", form.bcc)
            assertEquals("Hi", form.subject)
            assertEquals("hello", form.body)

            vm.edit { copy(body = "hello again") }
            vm.send()

            assertEquals(ComposeUiState.Sent, vm.settled())
            assertEquals(emptyList(), drafts(fake))
            val sent = fake.emails.single { it.id == fake.submissions.single().emailId }
            assertEquals("hello again", sent.body)
            assertEquals(listOf("m1@example.test"), sent.inReplyTo)
            assertEquals(setOf("sent"), sent.mailboxIds)
        }

    @Test
    fun `an untouched draft saves nothing and an edit replaces it`() =
        runViewModelTest {
            val fake = server()
            fake.emails += draftEmail()
            val vm = viewModel(fake, ComposeIntent.Draft("d1"))
            vm.editing()
            vm.idle()
            assertEquals(listOf("d1"), drafts(fake).map { it.id })

            vm.edit { copy(body = "changed") }
            vm.idle()

            assertEquals(listOf("changed"), drafts(fake).map { it.body })
        }

    @Test
    fun `a draft that was sent elsewhere says it is gone`() =
        runViewModelTest {
            val vm = viewModel(server(), ComposeIntent.Draft("d1"))

            val failed = assertIs<ComposeUiState.Failed>(vm.state.first { it !is ComposeUiState.Loading })

            assertEquals(uiText(Res.string.compose_draft_gone), failed.message)
        }

    @Test
    fun `sending before the first save files the draft then sends it`() =
        runViewModelTest {
            val fake = server()
            val vm = viewModel(fake)
            vm.editing()
            vm.edit { copy(to = "dan@example.test", body = "hello") }

            vm.send()

            assertEquals(ComposeUiState.Sent, vm.settled())
            assertEquals(emptyList(), drafts(fake))
            assertEquals(1, fake.submissions.size)
        }

    @Test
    fun `a refused submission leaves the text saved as a draft`() =
        runViewModelTest {
            val fake = server()
            fake.refuseSubmission = true
            val vm = viewModel(fake)
            vm.editing()
            vm.edit { copy(to = "dan@example.test", body = "hello") }

            vm.send()

            assertIs<ComposeUiState.Editing>(vm.settled())
            assertEquals(listOf("hello"), drafts(fake).map { it.body })
        }
}

private fun drafts(fake: FakeJmapServer) = fake.emails.filter { "\$draft" in it.keywords }

private fun draftEmail() =
    FakeEmail(
        id = "d1",
        threadId = "d1",
        mailboxIds = setOf("drafts"),
        keywords = setOf("\$draft"),
        subject = "Hi",
        from = OWN,
        receivedAt = "2024-03-02T12:00:00Z",
        to = listOf("dan@example.test"),
        cc = listOf("eve@example.test"),
        bcc = listOf("hidden@example.test"),
        body = "hello",
        inReplyTo = listOf("m1@example.test"),
    )
