package com.verdenroz.corvid.core.data.outbox

import com.verdenroz.corvid.core.model.Draft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Clock

sealed interface WriteOutcome {
    data class Queued(val id: String) : WriteOutcome

    /** Another process holds the outbox directory. */
    data object HeldElsewhere : WriteOutcome
}

/**
 * Queues a message while the device key is not held. It knows only the public [key], so it can seal but never open,
 * and it opens the outbox directory for the length of one write.
 */
class OutboxWriter(
    private val directory: File,
    private val key: SealKey,
    private val domain: String,
    private val clock: Clock = Clock.System,
) {
    suspend fun write(draft: Draft): WriteOutcome =
        withContext(Dispatchers.IO) {
            val store = OutboxStore.open(directory) ?: return@withContext WriteOutcome.HeldElsewhere
            store.use { WriteOutcome.Queued(OutboxQueue(it, key, domain, clock).enqueue(draft, replacesDraftId = null)) }
        }
}
