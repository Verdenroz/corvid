package com.verdenroz.corvid.feature.outbox

import com.verdenroz.corvid.core.data.ComposeRepository
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.data.outbox.Outbox
import com.verdenroz.corvid.core.data.outbox.OutboxQueue
import com.verdenroz.corvid.core.data.outbox.OutboxSealer
import com.verdenroz.corvid.core.data.outbox.OutboxSender
import com.verdenroz.corvid.core.data.outbox.OutboxState
import com.verdenroz.corvid.core.data.outbox.OutboxStore
import com.verdenroz.corvid.core.data.outbox.RejectionCategory
import com.verdenroz.corvid.core.data.outbox.Tidy
import com.verdenroz.corvid.core.model.Draft
import com.verdenroz.corvid.core.model.EmailAddress
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.KeyedTextResolver
import com.verdenroz.corvid.core.testing.ViewModelTestScope
import com.verdenroz.corvid.core.testing.client
import com.verdenroz.corvid.core.testing.runViewModelTest
import com.verdenroz.corvid.core.testing.withSendableAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

private val DEVICE_KEY = ByteArray(33) { (it * 7 + 1).toByte() }
private val OTHER_KEY = ByteArray(33) { (it * 5 + 3).toByte() }

private fun draft(subject: String) = Draft(to = listOf(EmailAddress(null, "bob@example.test")), subject = subject, body = "private body")

private class Rig(val fake: FakeJmapServer = FakeJmapServer().withSendableAccount()) : AutoCloseable {
    val directory = createTempDirectory().toFile()
    val store = checkNotNull(OutboxStore.open(directory))
    val queue = OutboxQueue(store, OutboxSealer.keyFor(DEVICE_KEY), "example.test")
    private lateinit var outbox: Outbox

    suspend fun outbox(): Outbox {
        val client = fake.client()
        val compose = ComposeRepository(client, MailboxRepository(client), KeyedTextResolver)
        val sender = OutboxSender(store, client, compose, DEVICE_KEY)
        outbox = Outbox(store, queue, sender, DEVICE_KEY, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        return outbox
    }

    fun put(
        id: String,
        state: OutboxState,
    ) = store.save(store.read(id)!!.copy(state = state))

    override fun close() = outbox.close()
}

class OutboxViewModelTest {
    private val rigs = mutableListOf<Rig>()

    @AfterTest
    fun closeRigs() = rigs.forEach(Rig::close)

    private fun rig() = Rig().also { rigs += it }

    private suspend fun ViewModelTestScope.viewModel(
        rig: Rig,
        clock: Clock = Clock.System,
    ) = track(OutboxViewModel(rig.outbox(), clock))

    private suspend fun OutboxViewModel.rows(until: (OutboxListState) -> Boolean = { true }) = state.first(until).rows

    @Test
    fun `a message whose response was lost shows as needing a decision with its recipients`() =
        runViewModelTest {
            val rig = rig()
            val id = rig.queue.enqueue(draft("Lunch"), null)
            rig.put(id, OutboxState.Unconfirmed)
            val vm = viewModel(rig)

            val row = vm.rows { it.rows.isNotEmpty() }.single()

            assertEquals(OutboxStatus.NeedsDecision, row.status)
            assertEquals("bob@example.test", row.recipients)
            assertEquals("Lunch", row.subject)
            assertTrue(row.canResend && row.canDiscard && row.mayHaveBeenSent)
        }

    @Test
    fun `a rejected message names why and can be sent again or discarded`() =
        runViewModelTest {
            val rig = rig()
            val id = rig.queue.enqueue(draft("Lunch"), null)
            rig.put(id, OutboxState.Rejected(RejectionCategory.Refused))
            val vm = viewModel(rig)

            val row = vm.rows { it.rows.isNotEmpty() }.single()

            assertEquals(OutboxStatus.NotSent, row.status)
            assertEquals(RejectionCategory.Refused, row.rejection)
            assertTrue(row.canResend && row.canDiscard && !row.mayHaveBeenSent)
        }

    @Test
    fun `sending again acts on the chosen message only`() =
        runViewModelTest {
            val rig = rig()
            val first = rig.queue.enqueue(draft("First"), null)
            val second = rig.queue.enqueue(draft("Second"), null)
            rig.put(first, OutboxState.Rejected(RejectionCategory.Refused))
            rig.put(second, OutboxState.Rejected(RejectionCategory.Refused))
            val vm = viewModel(rig)

            vm.resend(second)

            val rows = vm.rows { rows -> rows.rows.any { it.id == second && it.status == OutboxStatus.Sent } }
            assertEquals(OutboxStatus.NotSent, rows.single { it.id == first }.status)
            assertEquals(1, rig.fake.submissions.size)
        }

    @Test
    fun `discarding acts on the chosen message only and asks first`() =
        runViewModelTest {
            val rig = rig()
            val first = rig.queue.enqueue(draft("First"), null)
            val second = rig.queue.enqueue(draft("Second"), null)
            rig.put(first, OutboxState.Unconfirmed)
            rig.put(second, OutboxState.Unconfirmed)
            val vm = viewModel(rig)

            vm.askDiscard(second)
            assertEquals(second, vm.state.first { it.confirming != null }.confirming)
            vm.discard(second)

            val rows = vm.rows { rows -> rows.rows.size == 1 }
            assertEquals(first, rows.single().id)
            assertNull(vm.state.first { it.confirming == null }.confirming)
        }

    @Test
    fun `a message sealed to another key shows no text and offers only discard`() =
        runViewModelTest {
            val rig = rig()
            OutboxQueue(rig.store, OutboxSealer.keyFor(OTHER_KEY), "example.test").enqueue(draft("Secret"), null)
            val vm = viewModel(rig)

            val row = vm.rows { it.rows.isNotEmpty() }.single()

            assertEquals(OutboxStatus.SealedToPreviousKey, row.status)
            assertNull(row.recipients)
            assertNull(row.subject)
            assertTrue(!row.canResend && row.canDiscard)
        }

    @Test
    fun `a message whose saved copy is missing shows no text and offers only discard`() =
        runViewModelTest {
            val rig = rig()
            val id = rig.queue.enqueue(draft("Secret"), null)
            rig.directory.resolve("$id.msg").delete()
            val vm = viewModel(rig)

            val row = vm.rows { it.rows.isNotEmpty() }.single()

            assertEquals(OutboxStatus.CannotOpen, row.status)
            assertNull(row.recipients)
            assertNull(row.subject)
            assertTrue(!row.canResend && row.canDiscard)
        }

    @Test
    fun `a message sent within the day is listed with no text and an older one is not`() =
        runViewModelTest {
            val rig = rig()
            val now = Instant.fromEpochMilliseconds(100.hours.inWholeMilliseconds)

            val recent = rig.queue.enqueue(draft("Recent"), null)
            val old = rig.queue.enqueue(draft("Old"), null)
            rig.put(recent, OutboxState.Sent("e1", now.toEpochMilliseconds() - 1.hours.inWholeMilliseconds, Tidy()))
            rig.put(old, OutboxState.Sent("e2", now.toEpochMilliseconds() - 30.hours.inWholeMilliseconds, Tidy()))
            val vm =
                viewModel(
                    rig,
                    clock =
                        object : Clock {
                            override fun now() = now
                        },
                )

            val row = vm.rows { it.rows.isNotEmpty() }.single()

            assertEquals(recent, row.id)
            assertEquals(OutboxStatus.Sent, row.status)
            assertNull(row.subject)
            assertIs<Instant>(row.sentAt)
        }
}
