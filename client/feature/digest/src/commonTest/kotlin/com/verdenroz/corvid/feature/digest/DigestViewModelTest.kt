package com.verdenroz.corvid.feature.digest

import com.verdenroz.corvid.core.data.DigestRepository
import com.verdenroz.corvid.core.testing.FakeDigest
import com.verdenroz.corvid.core.testing.FakeDigestItem
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.ViewModelTestScope
import com.verdenroz.corvid.core.testing.client
import com.verdenroz.corvid.core.testing.runViewModelTest
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private suspend fun DigestViewModel.settled(): DigestState = state.first { it !is DigestState.Loading }

private fun item(summary: String? = null) = FakeDigestItem(summary = summary)

private suspend fun ViewModelTestScope.viewModel(
    fake: FakeJmapServer,
    id: String?,
) = track(DigestViewModel(DigestRepository(fake.client()), id))

class DigestViewModelTest {
    private val fake =
        FakeJmapServer().apply {
            digests.serve(
                FakeDigest("d1", groups = listOf("Old" to listOf(item()))),
                FakeDigest("d2", groups = listOf("Work" to listOf(item("Moved to Friday.")), null to listOf(item()))),
            )
        }

    @Test
    fun `no id shows the latest digest`() =
        runViewModelTest {
            val ready = assertIs<DigestState.Ready>(viewModel(fake, null).settled())

            assertEquals("d2", ready.digest.id)
            assertTrue(ready.anySummary)
        }

    @Test
    fun `an id shows that digest`() =
        runViewModelTest {
            val ready = assertIs<DigestState.Ready>(viewModel(fake, "d1").settled())

            assertEquals("d1", ready.digest.id)
            assertFalse(ready.anySummary)
        }

    @Test
    fun `an id the server does not hold is empty`() =
        runViewModelTest {
            assertEquals(DigestState.Empty, viewModel(fake, "d9").settled())
        }

    @Test
    fun `a server with no digest yet is empty`() =
        runViewModelTest {
            assertEquals(DigestState.Empty, viewModel(FakeJmapServer(), null).settled())
        }

    @Test
    fun `a digest the app cannot read is a failure with a message`() =
        runViewModelTest {
            fake.digests.malformed = true

            assertIs<DigestState.Failed>(viewModel(fake, null).settled())
        }

    @Test
    fun `a digest with no mail is empty`() =
        runViewModelTest {
            val quiet = FakeJmapServer().apply { digests.serve(FakeDigest("d1", groups = listOf("Work" to emptyList()))) }

            assertEquals(DigestState.Empty, viewModel(quiet, null).settled())
        }

    @Test
    fun `groups with no items are dropped`() =
        runViewModelTest {
            val groups = listOf("Quiet" to emptyList(), "Work" to listOf(item()))
            val mixed = FakeJmapServer().apply { digests.serve(FakeDigest("d1", groups = groups)) }

            val ready = assertIs<DigestState.Ready>(viewModel(mixed, null).settled())

            assertEquals(listOf("Work"), ready.digest.groups.map { it.label })
        }
}
