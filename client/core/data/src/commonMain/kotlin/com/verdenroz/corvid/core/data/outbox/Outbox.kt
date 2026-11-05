package com.verdenroz.corvid.core.data.outbox

import com.verdenroz.corvid.core.model.Draft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** One unlock's outbox. Sending runs on [scope], which closing cancels, so a caller that stops waiting does not interrupt a send. */
class Outbox(
    private val store: OutboxStore,
    private val queue: OutboxQueue,
    private val sender: OutboxSender,
    private val deviceKey: ByteArray,
    private val scope: CoroutineScope,
) : AutoCloseable {
    private val results = MutableStateFlow(emptyMap<String, ItemResult>())
    private val _entries = MutableStateFlow(read())
    val entries: StateFlow<List<OutboxEntry>> = _entries

    fun shouldStoreKey(stored: String?): Boolean = OutboxSealer.shouldStoreKey(deviceKey, stored, store)

    fun enqueue(
        draft: Draft,
        replacesDraftId: String?,
    ): String = queue.enqueue(draft, replacesDraftId).also { refresh() }

    suspend fun drain(): List<Drained> =
        scope.async {
            refresh()
            sender.drain().also { drained ->
                results.value += drained.associate { it.id to it.result }
                refresh()
            }
        }.await()

    fun drainSoon() {
        scope.launch { drain() }
    }

    suspend fun resend(id: String) {
        scope.async { sender.resend(id) }.await()
        refresh()
        drainSoon()
    }

    suspend fun discard(id: String): Boolean = scope.async { sender.discard(id) }.await().also { refresh() }

    private fun refresh() {
        _entries.value = read()
    }

    override fun close() {
        scope.cancel()
        store.close()
    }

    private fun read(): List<OutboxEntry> = store.list().items.map { OutboxEntry(it, contents(it), results.value[it.id]) }

    private fun contents(item: OutboxItem): Contents? {
        if (item.state is OutboxState.Sent) return null
        val sealed = (store.readMessage(item.id) as? MessageRead.Found)?.message ?: return Contents.Unreadable
        return when (val opened = OutboxSealer.open(item.id, sealed, deviceKey)) {
            is OpenResult.Opened -> Contents.Readable(opened.message.draft)
            is OpenResult.KeyMismatch -> Contents.SealedToOtherKey
            is OpenResult.Corrupt -> Contents.Unreadable
        }
    }
}
