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

    /** No usable public key is stored yet, since the device has not unlocked once. */
    data object NoKey : WriteOutcome
}

/** [waiting] messages will be sent on their own; [needDecision] wait on the user to send them again or discard them. */
data class OutboxWaiting(val waiting: Int, val needDecision: Int)

/** What [directory] holds, counted from state files alone; null while another store holds it. */
suspend fun waitingInOutbox(directory: File): OutboxWaiting? =
    withContext(Dispatchers.IO) {
        OutboxStore.withStore(directory) { store ->
            val unsent = store.list().items.map { it.state }.filter { it !is OutboxState.Sent }
            val (needDecision, waiting) = unsent.partition { it.waitsOnUser }
            OutboxWaiting(waiting.size, needDecision.size)
        }
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
            val queued = OutboxStore.withStore(directory) { OutboxQueue(it, key, domain, clock).enqueue(draft, replacesDraftId = null) }
            queued?.let { WriteOutcome.Queued(it) } ?: WriteOutcome.HeldElsewhere
        }
}
