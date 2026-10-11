package com.verdenroz.corvid.feature.compose

import androidx.lifecycle.viewModelScope
import com.verdenroz.corvid.core.data.ComposeRepository
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.data.PickedFile
import com.verdenroz.corvid.core.data.fileSize
import com.verdenroz.corvid.core.model.ComposeIntent
import com.verdenroz.corvid.core.testing.FakeAttachment
import com.verdenroz.corvid.core.testing.FakeEmail
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.KeyedTextResolver
import com.verdenroz.corvid.core.testing.ViewModelTestScope
import com.verdenroz.corvid.core.testing.client
import com.verdenroz.corvid.core.testing.runViewModelTest
import com.verdenroz.corvid.core.testing.withSendableAccount
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.feature.compose.resources.Res
import com.verdenroz.corvid.feature.compose.resources.compose_too_large
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val REPORT = FakeAttachment(blobId = "b-report", name = "report.pdf", type = "application/pdf", size = 2048)

class ComposeAttachmentsTest {
    private fun server() =
        FakeJmapServer().withSendableAccount().also {
            it.emails +=
                FakeEmail(
                    id = "orig",
                    threadId = "t1",
                    mailboxIds = setOf("inbox"),
                    subject = "Numbers",
                    from = "alice@example.test",
                    receivedAt = "2024-03-01T12:00:00Z",
                    body = "attached",
                    attachments = listOf(REPORT),
                )
        }

    private suspend fun ViewModelTestScope.viewModel(
        fake: FakeJmapServer,
        intent: ComposeIntent = ComposeIntent.New,
    ): ComposeViewModel {
        val client = fake.client()
        return track(ComposeViewModel(ComposeRepository(client, MailboxRepository(client), KeyedTextResolver), intent))
    }

    private suspend fun ComposeViewModel.editing() = editingWhere { true }

    private suspend fun ComposeViewModel.editingWhere(test: (ComposeUiState.Editing) -> Boolean) =
        state.first { it is ComposeUiState.Editing && test(it) } as ComposeUiState.Editing

    private suspend fun ComposeViewModel.idle() {
        while (true) {
            val running = viewModelScope.coroutineContext.job.children.firstOrNull() ?: return
            running.join()
        }
    }

    private fun file(
        name: String,
        text: String,
    ) = PickedFile(name, "text/plain", text.length.toLong()) { text.encodeToByteArray() }

    @Test
    fun `a picked file joins the form once the server holds it and goes out with the message`() =
        runViewModelTest {
            val fake = server()
            val vm = viewModel(fake)
            vm.editing()
            vm.edit { copy(to = "dan@example.test", subject = "Minutes") }

            vm.attach(listOf(file("minutes.txt", "agreed")))
            val attached = vm.editingWhere { it.form.attachments.isNotEmpty() }.form.attachments.single()
            vm.send()

            assertEquals(ComposeUiState.Sent, vm.state.first { it is ComposeUiState.Sent })
            val sent = fake.emails.single { it.id == fake.submissions.single().emailId }
            assertEquals(listOf(FakeAttachment(attached.blobId, "minutes.txt", "text/plain", 6)), sent.attachments)
        }

    @Test
    fun `send waits while a file is still uploading`() =
        runViewModelTest {
            val fake = server()
            val vm = viewModel(fake)
            vm.editing()
            vm.edit { copy(to = "dan@example.test") }
            val release = CompletableDeferred<Unit>()

            vm.attach(listOf(PickedFile("slow.txt", "text/plain", 4) { release.await().let { "slow".encodeToByteArray() } }))
            assertEquals(listOf("slow.txt"), vm.editing().uploading)
            vm.send()

            assertTrue(fake.submissions.isEmpty())
            release.complete(Unit)
            vm.idle()
            assertEquals(emptyList(), vm.editing().uploading)
        }

    @Test
    fun `a file over the server's limit is named with the limit and never attached`() =
        runViewModelTest {
            val fake = server()
            fake.maxSizeUpload = 3
            val vm = viewModel(fake)
            vm.editing()

            vm.attach(listOf(file("big.txt", "four")))

            val editing = vm.editingWhere { it.attachError != null }
            assertEquals(uiText(Res.string.compose_too_large, "big.txt", fileSize(3)), editing.attachError)
            assertEquals(emptyList(), editing.form.attachments)
        }

    @Test
    fun `a forward opens with the original's files and a removed one stays out of the draft`() =
        runViewModelTest {
            val fake = server()
            val vm = viewModel(fake, ComposeIntent.Forward("orig"))
            val forwarded = vm.editing().form.attachments.single()
            assertEquals("b-report", forwarded.blobId)

            vm.detach(forwarded)
            vm.edit { copy(to = "dan@example.test") }
            vm.send()

            vm.state.first { it is ComposeUiState.Sent }
            assertEquals(emptyList(), fake.emails.single { it.id == fake.submissions.single().emailId }.attachments)
        }
}
