package com.verdenroz.corvid.feature.mailbox

import androidx.lifecycle.viewModelScope
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.jmap.AuthLatch
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.createJmapHttpClient
import com.verdenroz.corvid.core.testing.FakeEmail
import com.verdenroz.corvid.core.testing.FakeJmapServer
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

private const val BASE_URL = "http://127.0.0.1:9999"

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {
    // Same reasoning as InboxViewModelTest: viewModelScope resolves to
    // Dispatchers.Main, structurally unrelated to runTest's own scheduler.
    private val testDispatcher = UnconfinedTestDispatcher()
    private var viewModel: ReaderViewModel? = null

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

    private suspend fun repository(fake: FakeJmapServer): MailboxRepository {
        val client = JmapClient.connect(createJmapHttpClient(fake.engine), AuthLatch(), BASE_URL)
        return MailboxRepository(client)
    }

    @Test
    fun `an html-only message reaches the screen already flattened to text`() =
        runTest(testDispatcher) {
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
            val vm = ReaderViewModel(repository(fake), "e1").also { viewModel = it }

            val loaded = vm.state.first { it is ReaderUiState.Loaded } as ReaderUiState.Loaded

            assertEquals("hello world", loaded.email.body)
        }

    @Test
    fun `a truncated body carries the flag through to the screen`() =
        runTest(testDispatcher) {
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
            val vm = ReaderViewModel(repository(fake), "e1").also { viewModel = it }

            val loaded = vm.state.first { it is ReaderUiState.Loaded } as ReaderUiState.Loaded

            assertEquals(true, loaded.email.bodyTruncated)
        }

    @Test
    fun `a message gone by the time it's opened reaches a distinct state`() =
        runTest(testDispatcher) {
            val fake = FakeJmapServer()
            val vm = ReaderViewModel(repository(fake), "gone").also { viewModel = it }

            vm.state.first { it is ReaderUiState.Gone }
        }
}
