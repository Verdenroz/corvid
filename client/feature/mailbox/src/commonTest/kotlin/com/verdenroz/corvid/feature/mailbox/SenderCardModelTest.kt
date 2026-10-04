package com.verdenroz.corvid.feature.mailbox

import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.data.SenderRepository
import com.verdenroz.corvid.core.testing.FakeEmail
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.client
import com.verdenroz.corvid.core.testing.withSendableAccount
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

private const val ADA = "ada@analytical.test"

private fun from(
    id: String,
    address: String,
    second: Long,
) = FakeEmail(id, id, setOf("inbox"), "s", address, Instant.fromEpochSeconds(second).toString())

private suspend fun model(fake: FakeJmapServer): SenderCardModel {
    val client = fake.client()
    return SenderCardModel(SenderRepository(client, MailboxRepository(client)), ADA)
}

class SenderCardModelTest {
    @Test
    fun `a card fills its history, counts and window, keeping only exact matches`() =
        runTest {
            val fake = FakeJmapServer().withSendableAccount()
            fake.emails += from("a1", ADA, 1)
            fake.emails += from("a2", ADA, 2)
            fake.emails += from("lookalike", "lada@analytical.test", 3)
            val model = model(fake)

            model.load()

            val state = model.state.value
            assertEquals(listOf("a2", "a1"), assertNotNull(state.recent).map { it.id })
            assertEquals(2, assertNotNull(state.counts).received)
            assertEquals(2, assertNotNull(state.window).size)
        }

    @Test
    fun `closing the card while it counts sends no further requests`() =
        runTest {
            val fake = FakeJmapServer().withSendableAccount()
            repeat(1200) { fake.emails += from("m$it", ADA, it.toLong()) }
            val model = model(fake)
            var queries = 0
            lateinit var job: Job
            fake.beforeEmailQuery = {
                queries++
                if (queries == 2) job.cancel()
            }

            job = launch { model.load() }
            job.join()

            // A finished card reads six pages: three to count, one for sent, one window, one history.
            assertTrue(queries <= 3, "read $queries pages after closing")
            assertNull(model.state.value.counts)
        }
}
