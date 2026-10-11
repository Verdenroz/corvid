package com.verdenroz.corvid.feature.mailbox

import com.verdenroz.corvid.core.data.Correction
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.model.HeaderField
import com.verdenroz.corvid.core.model.Label
import com.verdenroz.corvid.core.testing.FakeEmail
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.FakeMailbox
import com.verdenroz.corvid.core.testing.client
import com.verdenroz.corvid.core.testing.runViewModelTest
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.teach_refused
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals

class ReaderViewModelTest {
    private suspend fun repository(fake: FakeJmapServer): MailboxRepository {
        val client = fake.client()
        return MailboxRepository(client)
    }

    @Test
    fun `an html-only message reaches the screen already flattened to text`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            fake.emails +=
                FakeEmail(
                    "e1",
                    "e1",
                    setOf("inbox"),
                    "hello",
                    "a@example.test",
                    "2024-01-01T00:00:00Z",
                    bodyType = "text/html",
                    body = "<p>hello <b>world</b></p>",
                )
            val vm = track(ReaderViewModel(repository(fake), "e1"))

            val loaded = vm.state.first { it is ReaderUiState.Loaded } as ReaderUiState.Loaded

            assertEquals("hello world", loaded.messages.single().body)
        }

    @Test
    fun `a truncated body carries the flag through to the screen`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            fake.emails +=
                FakeEmail(
                    "e1",
                    "e1",
                    setOf("inbox"),
                    "hello",
                    "a@example.test",
                    "2024-01-01T00:00:00Z",
                    body = "cut short",
                    bodyTruncated = true,
                )
            val vm = track(ReaderViewModel(repository(fake), "e1"))

            val loaded = vm.state.first { it is ReaderUiState.Loaded } as ReaderUiState.Loaded

            assertEquals(true, loaded.messages.single().bodyTruncated)
        }

    @Test
    fun `a message gone by the time it's opened reaches a distinct state`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            val vm = track(ReaderViewModel(repository(fake), "gone"))

            vm.state.first { it is ReaderUiState.Gone }
        }

    @Test
    fun `a thread is titled by the subject it started with, not the newest reply's`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            fake.emails += FakeEmail("e1", "t1", setOf("inbox"), "Lunch", "a@example.test", "2024-01-01T00:00:00Z", body = "noon?")
            fake.emails += FakeEmail("e2", "t1", setOf("inbox"), "RE: Re: Lunch", "b@example.test", "2024-01-02T00:00:00Z", body = "yes")
            val vm = track(ReaderViewModel(repository(fake), "e2"))

            val loaded = vm.state.first { it is ReaderUiState.Loaded } as ReaderUiState.Loaded

            assertEquals("RE: Re: Lunch", loaded.newest.subject)
            assertEquals("Lunch", loaded.subject)
        }

    private fun guessed() =
        FakeEmail(
            "g1",
            "g1",
            setOf("inbox"),
            "s",
            "ada@x.test",
            "2024-01-01T00:00:00Z",
            keywords = setOf("\$seen", "corvid-llm-done", "corvid-llm-label-personal"),
            body = "b",
        )

    @Test
    fun `answering a guess shows the labels the server now holds`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            fake.emails += guessed()
            val vm = track(ReaderViewModel(repository(fake), "g1"))
            vm.state.first { it is ReaderUiState.Loaded }

            vm.answer("g1", Correction.Right(Label.Guess("personal")))

            val after = vm.state.first { it is ReaderUiState.Loaded && "personal" in it.messages.single().keywords } as ReaderUiState.Loaded
            assertEquals(setOf("\$seen", "corvid-llm-done", "personal"), after.messages.single().keywords)
            assertEquals(null, after.teach["g1"])
        }

    @Test
    fun `answering a guess rereads only the labels, not the message`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            fake.emails += guessed()
            val vm = track(ReaderViewModel(repository(fake), "g1"))
            vm.state.first { it is ReaderUiState.Loaded }
            fake.emails[0] = fake.emails[0].copy(body = "edited on the server")

            vm.answer("g1", Correction.Right(Label.Guess("personal")))

            val after = vm.state.first { it is ReaderUiState.Loaded && "personal" in it.messages.single().keywords } as ReaderUiState.Loaded
            assertEquals("b", after.messages.single().body)
        }

    @Test
    fun `an answer the server refuses keeps the guess and says why`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            fake.emails += guessed()
            val vm = track(ReaderViewModel(repository(fake), "g1"))
            vm.state.first { it is ReaderUiState.Loaded }
            fake.emails.clear()

            vm.answer("g1", Correction.Wrong(Label.Guess("personal")))

            val failed = vm.state.first { it is ReaderUiState.Loaded && it.teach["g1"]?.error != null } as ReaderUiState.Loaded
            assertEquals(uiText(Res.string.teach_refused, UiText.FromServer("no such email")), failed.teach["g1"]?.error)
            assertEquals(setOf("\$seen", "corvid-llm-done", "corvid-llm-label-personal"), failed.messages.single().keywords)
        }

    private fun thread(fake: FakeJmapServer) {
        fun message(
            n: Int,
            unread: Boolean = false,
        ) = FakeEmail(
            "m$n",
            "t",
            setOf("inbox"),
            "s",
            "a@x.test",
            "2024-01-0${n}T00:00:00Z",
            keywords = if (unread) emptySet() else setOf("\$seen"),
            body = "body $n",
        )
        fake.emails += listOf(message(1), message(2), message(3, unread = true), message(4), message(5))
    }

    @Test
    fun `a thread reads oldest first with the newest and every unread message expanded`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            thread(fake)
            val vm = track(ReaderViewModel(repository(fake), "m5"))

            val loaded = vm.state.first { it is ReaderUiState.Loaded } as ReaderUiState.Loaded

            assertEquals(listOf("m1", "m2", "m3", "m4", "m5"), loaded.messages.map { it.id })
            assertEquals(setOf("m3", "m5"), loaded.expanded)
        }

    @Test
    fun `opening a collapsed message from elsewhere shows it in full`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            thread(fake)
            val vm = track(ReaderViewModel(repository(fake), "m1"))

            val loaded = vm.state.first { it is ReaderUiState.Loaded } as ReaderUiState.Loaded

            assertEquals(setOf("m1", "m3", "m5"), loaded.expanded)
        }

    @Test
    fun `toggling a message expands and collapses it`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            thread(fake)
            val vm = track(ReaderViewModel(repository(fake), "m5"))
            vm.state.first { it is ReaderUiState.Loaded }

            vm.toggle("m2")
            val opened = vm.state.first { it is ReaderUiState.Loaded && "m2" in it.expanded } as ReaderUiState.Loaded
            vm.toggle("m2")

            assertEquals(setOf("m2", "m3", "m5"), opened.expanded)
            vm.state.first { it is ReaderUiState.Loaded && "m2" !in it.expanded }
        }

    @Test
    fun `opening a thread marks its unread messages read and tells the lists`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            thread(fake)
            val repository = repository(fake)
            coroutineScope {
                val patch = async { repository.keywordPatches.first() }
                track(ReaderViewModel(repository, "m5"))

                assertEquals("m3", patch.await().emailId)
            }
            assertEquals(true, "\$seen" in fake.emails.single { it.id == "m3" }.keywords)
        }

    @Test
    fun `answering on one message leaves the others' teach state alone`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            thread(fake)
            fake.emails.replaceAll { if (it.id == "m3") it.copy(keywords = it.keywords + "corvid-llm-label-personal") else it }
            val vm = track(ReaderViewModel(repository(fake), "m5"))
            vm.state.first { it is ReaderUiState.Loaded }

            vm.answer("m3", Correction.Right(Label.Guess("personal")))

            val after = vm.state.first { it is ReaderUiState.Loaded && "personal" in it.messages.single { m -> m.id == "m3" }.keywords }
            assertEquals(emptyMap(), (after as ReaderUiState.Loaded).teach)
        }

    @Test
    fun `headers are fetched on first show and hidden on the second`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            fake.emails +=
                FakeEmail("e1", "e1", setOf("inbox"), "hello", "a@example.test", "2024-01-01T00:00:00Z", body = "hi")
                    .copy(headers = mapOf("Received" to listOf("from relay")))
            val vm = track(ReaderViewModel(repository(fake), "e1"))
            vm.state.first { it is ReaderUiState.Loaded }

            vm.toggleHeaders("e1")
            val shown = vm.state.first { (it as? ReaderUiState.Loaded)?.headers?.get("e1") is HeadersState.Shown } as ReaderUiState.Loaded
            vm.toggleHeaders("e1")

            assertEquals(listOf(HeaderField("Received", "from relay")), (shown.headers.getValue("e1") as HeadersState.Shown).fields)
            assertEquals(emptyMap(), (vm.state.value as ReaderUiState.Loaded).headers)
        }

    @Test
    fun `a thread in the inbox reads as an inbox thread and one only in trash as trash`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            fake.mailboxes += FakeMailbox("inbox", "Inbox", "inbox")
            fake.mailboxes += FakeMailbox("trash", "Trash", "trash")
            fake.emails += FakeEmail("e1", "e1", setOf("inbox"), "hello", "a@example.test", "2024-01-01T00:00:00Z", body = "hi")
            fake.emails += FakeEmail("e2", "e2", setOf("trash"), "bye", "a@example.test", "2024-01-01T00:00:00Z", body = "hi")
            val repository = repository(fake)

            val inbox = track(ReaderViewModel(repository, "e1")).state.first { it is ReaderUiState.Loaded } as ReaderUiState.Loaded
            val trash = track(ReaderViewModel(repository, "e2")).state.first { it is ReaderUiState.Loaded } as ReaderUiState.Loaded

            assertEquals(ListKind.Inbox, inbox.kind)
            assertEquals(ListKind.Trash, trash.kind)
        }

    @Test
    fun `a thread opened from a list takes that list's kind, not one read off its messages`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            fake.mailboxes += FakeMailbox("sent", "Sent", "sent")
            fake.mailboxes += FakeMailbox("trash", "Trash", "trash")
            fake.emails += FakeEmail("e1", "t", setOf("trash"), "hello", "a@example.test", "2024-01-01T00:00:00Z", body = "hi")
            fake.emails += FakeEmail("e2", "t", setOf("sent"), "Re: hello", "me@example.test", "2024-01-02T00:00:00Z", body = "hi")
            val repository = repository(fake)

            val fromTrash = track(ReaderViewModel(repository, "e1", ListKind.Trash)).state.first { it is ReaderUiState.Loaded }
            val fromNoList = track(ReaderViewModel(repository, "e1")).state.first { it is ReaderUiState.Loaded }

            assertEquals(ListKind.Trash, (fromTrash as ReaderUiState.Loaded).kind)
            assertEquals(ListKind.Other, (fromNoList as ReaderUiState.Loaded).kind)
        }

    @Test
    fun `a star set elsewhere shows on the open thread`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            fake.emails += FakeEmail("e1", "e1", setOf("inbox"), "hello", "a@example.test", "2024-01-01T00:00:00Z", body = "hi")
            val repository = repository(fake)
            val vm = track(ReaderViewModel(repository, "e1"))
            vm.state.first { it is ReaderUiState.Loaded }

            repository.updateKeywords("e1", mapOf("\$flagged" to true))

            vm.state.first { (it as? ReaderUiState.Loaded)?.newest?.keywords?.contains("\$flagged") == true }
        }
}
