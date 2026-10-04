package com.verdenroz.corvid.feature.compose

import androidx.lifecycle.viewModelScope
import com.verdenroz.corvid.core.data.ComposeRepository
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.jmap.AuthLatch
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.createJmapHttpClient
import com.verdenroz.corvid.core.testing.FakeEmail
import com.verdenroz.corvid.core.testing.FakeIdentity
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.FakeMailbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val BASE_URL = "http://127.0.0.1:9999"
private const val OWN = "verde@example.test"

@OptIn(ExperimentalCoroutinesApi::class)
class ComposeViewModelTest {
    // Same reasoning as InboxViewModelTest: viewModelScope resolves to Dispatchers.Main.
    private val testDispatcher = UnconfinedTestDispatcher()
    private var viewModel: ComposeViewModel? = null

    @BeforeTest
    fun setMain() = Dispatchers.setMain(testDispatcher)

    @AfterTest
    fun resetMain() {
        viewModel?.viewModelScope?.let { scope ->
            scope.cancel()
            runBlocking { scope.coroutineContext.job.join() }
        }
        Dispatchers.resetMain()
    }

    private fun server(): FakeJmapServer {
        val fake = FakeJmapServer()
        fake.mailboxes += FakeMailbox(id = "inbox", name = "Inbox", role = "inbox")
        fake.mailboxes += FakeMailbox(id = "drafts", name = "Drafts", role = "drafts")
        fake.mailboxes += FakeMailbox(id = "sent", name = "Sent", role = "sent")
        fake.identities += FakeIdentity(id = "i1", email = OWN)
        fake.emails +=
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
        return fake
    }

    private suspend fun viewModel(
        fake: FakeJmapServer,
        mode: ComposeMode,
    ): ComposeViewModel {
        val client = JmapClient.connect(createJmapHttpClient(fake.engine), AuthLatch(), BASE_URL)
        val mailboxes = MailboxRepository(client)
        return ComposeViewModel(ComposeRepository(client, mailboxes), mailboxes, mode).also { viewModel = it }
    }

    private suspend fun ComposeViewModel.editing() = state.first { it is ComposeUiState.Editing } as ComposeUiState.Editing

    private suspend fun ComposeViewModel.settled() = state.first { it !is ComposeUiState.Editing || !it.sending }

    @Test
    fun `an account with no identity says so instead of opening the form`() =
        runTest(testDispatcher) {
            val fake = server()
            fake.identities.clear()
            val vm = viewModel(fake, ComposeMode.New)

            val failed = assertIs<ComposeUiState.Failed>(vm.state.first { it !is ComposeUiState.Loading })

            assertEquals("This account has no sending identity.", failed.message)
        }

    @Test
    fun `sending a new message files it under sent and submits it`() =
        runTest(testDispatcher) {
            val fake = server()
            val vm = viewModel(fake, ComposeMode.New)
            vm.edit(vm.editing().form.copy(to = "dan@example.test, eve@example.test", subject = "Hi", body = "hello"))

            vm.send()

            assertEquals(ComposeUiState.Sent, vm.settled())
            val email = fake.emails.single { it.id == fake.submissions.single().emailId }
            assertEquals(setOf("sent"), email.mailboxIds)
            assertEquals(listOf("dan@example.test", "eve@example.test"), email.to)
        }

    @Test
    fun `a reply opens prefilled with the sender, subject and quoted body`() =
        runTest(testDispatcher) {
            val vm = viewModel(server(), ComposeMode.Reply("orig", all = false))

            val form = vm.editing().form

            assertEquals("alice@example.test", form.to)
            assertEquals("Re: Lunch", form.subject)
            assertTrue(form.body.endsWith("wrote:\n> see you at noon"))
        }

    @Test
    fun `a sent reply carries the original's message id as in-reply-to and references`() =
        runTest(testDispatcher) {
            val fake = server()
            val vm = viewModel(fake, ComposeMode.Reply("orig", all = false))
            vm.editing()

            vm.send()

            assertEquals(ComposeUiState.Sent, vm.settled())
            val email = fake.emails.single { it.id == fake.submissions.single().emailId }
            assertEquals(listOf("m1@example.test"), email.inReplyTo)
            assertEquals(listOf("m1@example.test"), email.references)
        }

    @Test
    fun `replying to a sender whose name contains a comma sends to one address`() =
        runTest(testDispatcher) {
            val fake = server()
            fake.emails.replaceAll { it.copy(from = "jane@example.test", fromName = "Doe, Jane") }
            val vm = viewModel(fake, ComposeMode.Reply("orig", all = false))
            assertEquals("\"Doe, Jane\" <jane@example.test>", vm.editing().form.to)

            vm.send()

            vm.settled()
            val email = fake.emails.single { it.id == fake.submissions.single().emailId }
            assertEquals(listOf("jane@example.test"), email.to)
        }

    @Test
    fun `reply all leaves the account's own address out of cc`() =
        runTest(testDispatcher) {
            val vm = viewModel(server(), ComposeMode.Reply("orig", all = true))

            assertEquals("bob@example.test", vm.editing().form.cc)
        }

    @Test
    fun `a forward opens with no recipient and the original below a header block`() =
        runTest(testDispatcher) {
            val vm = viewModel(server(), ComposeMode.Forward("orig"))

            val form = vm.editing().form

            assertEquals("", form.to)
            assertEquals("Fwd: Lunch", form.subject)
            assertTrue(form.body.contains("---------- Forwarded message ----------"))
        }

    @Test
    fun `replying to a message that is gone reaches a distinct state`() =
        runTest(testDispatcher) {
            val vm = viewModel(server(), ComposeMode.Reply("missing", all = false))

            assertEquals(ComposeUiState.Gone, vm.state.first { it !is ComposeUiState.Loading })
        }

    @Test
    fun `a failed submission keeps the text and shows an error`() =
        runTest(testDispatcher) {
            val fake = server()
            fake.refuseSubmission = true
            val vm = viewModel(fake, ComposeMode.New)
            val typed = vm.editing().form.copy(to = "dan@example.test", subject = "Hi", body = "hello")
            vm.edit(typed)

            vm.send()

            val editing = assertIs<ComposeUiState.Editing>(vm.settled())
            assertEquals(typed, editing.form)
            assertEquals(false, editing.sending)
            assertNotNull(editing.error)
            assertTrue(fake.submissions.isEmpty())
        }

    @Test
    fun `sending with no recipient asks for one without calling the server`() =
        runTest(testDispatcher) {
            val fake = server()
            val vm = viewModel(fake, ComposeMode.New)
            vm.editing()
            val requestsBefore = fake.requestCount

            vm.send()

            assertEquals("Add a recipient.", assertIs<ComposeUiState.Editing>(vm.state.value).error)
            assertEquals(requestsBefore, fake.requestCount)
        }
}
