package com.verdenroz.corvid.feature.mailbox

import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.jmap.AuthLatch
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.createJmapHttpClient
import com.verdenroz.corvid.core.testing.FakeEmail
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.runViewModelTest
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals

private const val BASE_URL = "http://127.0.0.1:9999"

class ReaderViewModelTest {
    private suspend fun repository(fake: FakeJmapServer): MailboxRepository {
        val client = JmapClient.connect(createJmapHttpClient(fake.engine), AuthLatch(), BASE_URL)
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

            assertEquals("hello world", loaded.email.body)
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

            assertEquals(true, loaded.email.bodyTruncated)
        }

    @Test
    fun `a message gone by the time it's opened reaches a distinct state`() =
        runViewModelTest {
            val fake = FakeJmapServer()
            val vm = track(ReaderViewModel(repository(fake), "gone"))

            vm.state.first { it is ReaderUiState.Gone }
        }
}
