package com.verdenroz.corvid.core.data.outbox

import com.verdenroz.corvid.core.model.Draft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** One unlock's outbox. Sending runs on [scope], which closing cancels, so a caller that stops waiting does not interrupt a send. */
class Outbox(
    private val store: OutboxStore,
    private val queue: OutboxQueue,
    private val sender: OutboxSender,
    private val scope: CoroutineScope,
) : AutoCloseable {
    fun enqueue(
        draft: Draft,
        replacesDraftId: String?,
    ): String = queue.enqueue(draft, replacesDraftId)

    suspend fun drain(): List<Drained> = scope.async { sender.drain() }.await()

    suspend fun discard(id: String): Boolean = scope.async { sender.discard(id) }.await()

    fun drainSoon() {
        scope.launch { sender.drain() }
    }

    override fun close() {
        scope.cancel()
        store.close()
    }
}
