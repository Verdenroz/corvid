package com.verdenroz.corvid.feature.settings

import com.verdenroz.corvid.core.data.UpdateRepository
import com.verdenroz.corvid.core.data.userMessage
import com.verdenroz.corvid.core.jmap.JmapError
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.model.UpdateResult
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.FakeUpdate
import com.verdenroz.corvid.core.testing.ViewModelTestScope
import com.verdenroz.corvid.core.testing.client
import com.verdenroz.corvid.core.testing.runViewModelTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val CURRENT = FakeUpdate(running = "0.2.0", latest = "0.2.0", checkedAt = "2026-10-04T09:00:00Z")
private val AVAILABLE = CURRENT.copy(latest = "0.2.1")
private val BUSY = AVAILABLE.copy(busy = true)

private suspend fun ViewModelTestScope.viewModel(
    fake: FakeJmapServer,
    ceiling: Duration = 20.minutes,
): AboutViewModel =
    track(AboutViewModel(UpdateRepository(fake.client()), pollEvery = 3.seconds, ceiling = ceiling)).also {
        it.await {
                state ->
            state !is AboutState.Loading
        }
    }

private suspend fun AboutViewModel.await(until: (AboutState) -> Boolean): AboutState = state.first(until)

private fun AboutViewModel.ready(): AboutState.Ready = assertIs<AboutState.Ready>(state.value)

private fun AboutState.idle(): Boolean = this is AboutState.Ready && !working

class AboutViewModelTest {
    @Test
    fun `an available update is reported with its versions`() =
        runViewModelTest {
            val ready = viewModel(FakeJmapServer().apply { update = AVAILABLE }).ready()

            assertEquals(listOf("0.2.0", "0.2.1", true), listOf(ready.report.running, ready.report.latest, ready.report.updateAvailable))
            assertEquals(false, ready.working)
        }

    @Test
    fun `a server without the endpoint is unsupported, not failed`() =
        runViewModelTest {
            assertEquals(AboutState.Unsupported, viewModel(FakeJmapServer()).state.value)
        }

    @Test
    fun `a server error on the first read is a failure with the usual message`() =
        runViewModelTest {
            val fake =
                FakeJmapServer().apply {
                    update = CURRENT
                    updateBroken = true
                }

            assertEquals(AboutState.Failed(JmapException(JmapError.Server(500)).userMessage()), viewModel(fake).state.value)
        }

    @Test
    fun `a check posts check, shows checking while the server is busy and settles on the new result`() =
        runViewModelTest {
            val fake = FakeJmapServer().apply { update = CURRENT }
            val viewModel = viewModel(fake)
            fake.update = BUSY
            fake.updateAfterReads += AVAILABLE.copy(checkedAt = "2026-10-04T10:00:00Z")

            viewModel.check()
            assertEquals(UpdateActivity.Checking, viewModel.ready().activity)

            val busy = viewModel.await { it is AboutState.Ready && it.report.busy }
            assertEquals(UpdateActivity.Checking, assertIs<AboutState.Ready>(busy).activity)

            val settled = assertIs<AboutState.Ready>(viewModel.await { it.idle() })
            assertEquals(listOf("check"), fake.updateActions)
            assertNull(settled.activity)
            assertEquals("0.2.1", settled.report.latest)
        }

    @Test
    fun `an update rides out the restart and shows the final result and detail`() =
        runViewModelTest {
            val fake = FakeJmapServer().apply { update = AVAILABLE }
            val viewModel = viewModel(fake)

            viewModel.apply()
            assertEquals(UpdateActivity.Updating, viewModel.ready().activity)
            fake.updateReadsDropped = 2
            fake.update = FakeUpdate(running = "0.2.1", latest = "0.2.1", result = "updated", detail = "0.2.0 to 0.2.1")

            val settled = assertIs<AboutState.Ready>(viewModel.await { it.idle() })
            assertEquals(listOf("apply"), fake.updateActions)
            assertEquals(0, fake.updateReadsDropped)
            assertNull(settled.failure)
            assertEquals(
                Triple("0.2.1", UpdateResult.Updated, "0.2.0 to 0.2.1"),
                Triple(settled.report.running, settled.report.result, settled.report.detail),
            )
        }

    @Test
    fun `a rolled back update ends with the server's reason`() =
        runViewModelTest {
            val fake = FakeJmapServer().apply { update = AVAILABLE }
            val viewModel = viewModel(fake)

            viewModel.apply()
            fake.update = FakeUpdate(running = "0.2.0", latest = "0.2.1", result = "rolledBack", detail = "health check failed")

            val settled = assertIs<AboutState.Ready>(viewModel.await { it.idle() })
            assertEquals(UpdateResult.RolledBack, settled.report.result)
            assertEquals("health check failed", settled.report.detail)
        }

    @Test
    fun `opening while the server is already busy polls until it settles`() =
        runViewModelTest {
            val fake =
                FakeJmapServer().apply {
                    update = BUSY
                    updateAfterReads += CURRENT
                }
            val viewModel = viewModel(fake)

            assertTrue(viewModel.ready().working)
            assertNull(viewModel.ready().activity)

            viewModel.await { it.idle() }
        }

    @Test
    fun `a report the client cannot read ends the wait with a failure and keeps the last report`() =
        runViewModelTest {
            val fake = FakeJmapServer().apply { update = AVAILABLE }
            val viewModel = viewModel(fake)

            viewModel.check()
            fake.update = AVAILABLE.copy(result = "exploded")

            val ready = assertIs<AboutState.Ready>(viewModel.await { it is AboutState.Ready && it.failure != null })
            assertNull(ready.activity)
            assertNotNull(ready.failure)
            assertEquals(UpdateResult.Current, ready.report.result)
        }

    @Test
    fun `a request is ignored while the server is busy`() =
        runViewModelTest {
            val fake = FakeJmapServer().apply { update = BUSY }
            val viewModel = viewModel(fake)

            viewModel.check()
            viewModel.apply()

            assertEquals(emptyList(), fake.updateActions)
        }

    @Test
    fun `a not-busy report from before the request is not taken as the answer`() =
        runViewModelTest {
            val fake =
                FakeJmapServer().apply {
                    update = AVAILABLE
                    updateAfterReads += listOf(AVAILABLE, AVAILABLE.copy(checkedAt = "2026-10-04T10:00:00Z"))
                }
            val viewModel = viewModel(fake)

            viewModel.check()
            val settled = assertIs<AboutState.Ready>(viewModel.await { it.idle() })

            assertEquals(Instant.parse("2026-10-04T10:00:00Z"), settled.report.checkedAt)
        }

    @Test
    fun `polling stops at the ceiling and lets the user ask again`() =
        runViewModelTest {
            val fake = FakeJmapServer().apply { update = AVAILABLE }
            val viewModel = viewModel(fake, ceiling = 9.seconds)

            viewModel.check()
            fake.update = BUSY
            val given = assertIs<AboutState.Ready>(viewModel.await { it is AboutState.Ready && it.abandoned })

            assertNull(given.activity)
            assertNotNull(given.failure)
            assertEquals(false, given.working)
            viewModel.check()
            assertEquals(UpdateActivity.Checking, viewModel.ready().activity)
        }

    @Test
    fun `stopping ends polling`() =
        runViewModelTest {
            val fake = FakeJmapServer().apply { update = BUSY }
            val viewModel = viewModel(fake)
            val before = fake.requestCount

            viewModel.stop()
            delay(10.seconds)
            withContext(Dispatchers.Default) { delay(200.milliseconds) }

            assertEquals(before, fake.requestCount)
        }
}
