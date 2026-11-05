package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.data.outbox.ItemResult
import com.verdenroz.corvid.core.data.outbox.OutboxQueue
import com.verdenroz.corvid.core.data.outbox.OutboxSealer
import com.verdenroz.corvid.core.data.outbox.OutboxSender
import com.verdenroz.corvid.core.data.outbox.OutboxStore
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.model.Draft
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.client
import kotlin.io.path.createTempDirectory

private val DEVICE_KEY = ByteArray(33) { (it * 7 + 1).toByte() }

/** Queues [draft] and drains the outbox once, which is how every message is sent. */
internal suspend fun ComposeRepository.sendNew(
    fake: FakeJmapServer,
    draft: Draft,
): ItemResult = sendThroughOutbox(fake.client(), draft)

internal suspend fun ComposeRepository.sendThroughOutbox(
    client: JmapClient,
    draft: Draft,
): ItemResult =
    checkNotNull(OutboxStore.open(createTempDirectory().toFile())).use { store ->
        OutboxQueue(store, OutboxSealer.keyFor(DEVICE_KEY), "example.test").enqueue(draft, null)
        OutboxSender(store, client, this, DEVICE_KEY).drain().single().result
    }
