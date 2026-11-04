package com.verdenroz.corvid.feature.compose

import com.verdenroz.corvid.core.data.ComposeRepository
import com.verdenroz.corvid.core.data.outbox.Outbox
import com.verdenroz.corvid.core.data.outbox.OutboxQueue
import com.verdenroz.corvid.core.data.outbox.OutboxSealer
import com.verdenroz.corvid.core.data.outbox.OutboxSender
import com.verdenroz.corvid.core.data.outbox.OutboxStore
import com.verdenroz.corvid.core.jmap.JmapClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.io.path.createTempDirectory
import kotlin.test.assertNotNull

private val DEVICE_KEY = ByteArray(33) { (it * 7 + 1).toByte() }

internal class TestOutbox(client: JmapClient, compose: ComposeRepository) : AutoCloseable {
    val store = assertNotNull(OutboxStore.open(createTempDirectory().toFile()))
    val outbox =
        Outbox(
            store,
            OutboxQueue(store, OutboxSealer.keyFor(DEVICE_KEY), "example.test"),
            OutboxSender(store, client, compose, DEVICE_KEY),
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )

    override fun close() = outbox.close()
}
