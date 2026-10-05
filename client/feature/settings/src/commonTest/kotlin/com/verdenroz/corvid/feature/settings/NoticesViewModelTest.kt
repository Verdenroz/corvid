package com.verdenroz.corvid.feature.settings

import com.verdenroz.corvid.core.data.NoticeRepository
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.FakeNotice
import com.verdenroz.corvid.core.testing.client
import com.verdenroz.corvid.core.testing.runViewModelTest
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private suspend fun NoticesViewModel.settled(): NoticesState = state.first { it !is NoticesState.Loading }

class NoticesViewModelTest {
    @Test
    fun `the list is every notice the server holds, newest first`() =
        runViewModelTest {
            val fake = FakeJmapServer().apply { notices = listOf(FakeNotice(1), FakeNotice(2), FakeNotice(3)) }

            val viewModel = track(NoticesViewModel(NoticeRepository(fake.client())))

            val ready = assertIs<NoticesState.Ready>(viewModel.settled())
            assertEquals(listOf(3L, 2L, 1L), ready.notices.map { it.id })
            assertEquals(listOf<String?>(null), fake.noticeReads)
        }

    @Test
    fun `a server with no notices is an empty list`() =
        runViewModelTest {
            val fake = FakeJmapServer().apply { notices = emptyList() }

            val viewModel = track(NoticesViewModel(NoticeRepository(fake.client())))

            assertEquals(NoticesState.Ready(emptyList()), viewModel.settled())
        }

    @Test
    fun `a server without the feed is unsupported`() =
        runViewModelTest {
            val viewModel = track(NoticesViewModel(NoticeRepository(FakeJmapServer().client())))

            assertEquals(NoticesState.Unsupported, viewModel.settled())
        }

    @Test
    fun `a server error is a failure with a message`() =
        runViewModelTest {
            val fake = FakeJmapServer().apply { notices = listOf(FakeNotice(1)) }
            fake.noticesBroken = true

            val viewModel = track(NoticesViewModel(NoticeRepository(fake.client())))

            assertIs<NoticesState.Failed>(viewModel.settled())
        }
}
