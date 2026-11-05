package com.verdenroz.corvid.app

import com.verdenroz.corvid.app.di.outboxModule
import com.verdenroz.corvid.core.data.di.dataModule
import com.verdenroz.corvid.core.data.outbox.OutboxQueue
import com.verdenroz.corvid.core.data.outbox.OutboxSealer
import com.verdenroz.corvid.core.data.outbox.OutboxState
import com.verdenroz.corvid.core.data.outbox.OutboxStore
import com.verdenroz.corvid.core.model.Draft
import com.verdenroz.corvid.core.model.EmailAddress
import com.verdenroz.corvid.core.prefs.DevicePreferences
import com.verdenroz.corvid.core.prefs.DeviceSettings
import com.verdenroz.corvid.core.prefs.OutboxDirectory
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.InMemoryPreferencesStore
import com.verdenroz.corvid.core.testing.client
import com.verdenroz.corvid.core.testing.withSendableAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

private val FIRST_KEY = ByteArray(33) { (it * 7 + 1).toByte() }
private val SECOND_KEY = ByteArray(33) { (it * 5 + 3).toByte() }

private class Unlocking(scope: TestScope, val fake: FakeJmapServer = FakeJmapServer().withSendableAccount()) {
    val directory = createTempDirectory().toFile()
    val preferences = DevicePreferences(InMemoryPreferencesStore(), scope.backgroundScope, DeviceSettings())
    private val app =
        koinApplication {
            modules(
                dataModule,
                outboxModule,
                module {
                    single { OutboxDirectory(directory) }
                    single { preferences }
                },
            )
        }
    private val holder = UnlockedScopeHolder(app.koin)

    suspend fun unlock(key: ByteArray) {
        val scope = holder.open().also { it.declare(fake.client()) }
        app.koin.get<OutboxUnlock>().open(scope, key, "example.test")
    }

    fun lock() = holder.close()

    fun close() {
        holder.close()
        app.close()
    }

    fun queueWhileClosed(key: ByteArray) {
        val store = assertNotNull(OutboxStore.open(directory))
        val to = listOf(EmailAddress(null, "bob@example.test"))
        OutboxQueue(store, OutboxSealer.keyFor(key), "example.test").enqueue(Draft(to = to, subject = "queued", body = "hi"), null)
        store.close()
    }

    suspend fun storedKey() = preferences.outboxKey()

    fun access() = holder.current().get<OutboxAccess>()

    fun items() = access().outbox!!.entries.value.map { it.item }
}

private suspend fun until(condition: () -> Boolean) {
    withContext(Dispatchers.Default) { withTimeout(5.seconds) { while (!condition()) delay(10) } }
}

class OutboxUnlockTest {
    private val open = mutableListOf<Unlocking>()

    private fun TestScope.unlocking() = Unlocking(this).also { open += it }

    @AfterTest
    fun closeRigs() = open.forEach(Unlocking::close)

    @Test
    fun `a first unlock stores the key its device key derives`() =
        runTest {
            val unlocking = unlocking()

            unlocking.unlock(FIRST_KEY)

            assertEquals(OutboxSealer.keyFor(FIRST_KEY).encoded(), unlocking.storedKey())
        }

    @Test
    fun `a new device key replaces the stored key when no old mail remains`() =
        runTest {
            val unlocking = unlocking()
            unlocking.unlock(FIRST_KEY)
            unlocking.lock()

            unlocking.unlock(SECOND_KEY)

            assertEquals(OutboxSealer.keyFor(SECOND_KEY).encoded(), unlocking.storedKey())
        }

    @Test
    fun `a new device key keeps the stored key while mail is still sealed to it`() =
        runTest {
            val unlocking = unlocking()
            unlocking.unlock(FIRST_KEY)
            unlocking.lock()
            unlocking.queueWhileClosed(FIRST_KEY)

            unlocking.unlock(SECOND_KEY)

            assertEquals(OutboxSealer.keyFor(FIRST_KEY).encoded(), unlocking.storedKey())
        }

    @Test
    fun `unlocking while another process holds the outbox does not throw and leaves no outbox`() =
        runTest {
            val unlocking = unlocking()
            val other = assertNotNull(OutboxStore.open(unlocking.directory))

            unlocking.unlock(FIRST_KEY)

            assertNull(unlocking.access().outbox)
            assertNull(unlocking.storedKey())
            other.close()
        }

    @Test
    fun `unlocking sends mail that was queued before the unlock`() =
        runTest {
            val unlocking = unlocking()
            unlocking.queueWhileClosed(FIRST_KEY)

            unlocking.unlock(FIRST_KEY)

            until { unlocking.items().singleOrNull()?.state is OutboxState.Sent }
            assertEquals(1, unlocking.fake.emails.size)
        }
}
