package com.verdenroz.corvid.core.data.outbox

import com.verdenroz.corvid.core.model.Draft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One unlock's outbox. File access and sending run on [scope], which closing cancels, so a caller
 * that stops waiting does not interrupt a send and none blocks on the disk. [entries] fills in once the first read is done.
 */
class Outbox(
    private val store: OutboxStore,
    private val queue: OutboxQueue,
    private val sender: OutboxSender,
    private val deviceKey: ByteArray,
    private val scope: CoroutineScope,
) : AutoCloseable {
    private val results = MutableStateFlow(emptyMap<String, ItemResult>())
    private val refreshes = Mutex()
    private val _entries = MutableStateFlow(emptyList<OutboxEntry>())
    val entries: StateFlow<List<OutboxEntry>> = _entries
    private val _quarantined = MutableStateFlow(emptyList<String>())

    /** Ids of files the store refused to read; each can only be discarded. */
    val quarantined: StateFlow<List<String>> = _quarantined

    init {
        scope.launch { refresh() }
    }

    suspend fun shouldStoreKey(stored: String?): Boolean = onScope { OutboxSealer.shouldStoreKey(deviceKey, stored, store) }

    suspend fun enqueue(
        draft: Draft,
        replacesDraftId: String?,
    ): String = onScope { queue.enqueue(draft, replacesDraftId).also { refresh() } }

    suspend fun drain(): List<Drained> =
        onScope {
            refresh()
            sender.drain().also { drained ->
                results.value += drained.associate { it.id to it.result }
                refresh()
            }
        }

    fun drainSoon() {
        scope.launch { drain() }
    }

    suspend fun resend(id: String) {
        onScope {
            sender.resend(id)
            refresh()
        }
        drainSoon()
    }

    suspend fun discard(id: String): Boolean = onScope { sender.discard(id).also { refresh() } }

    suspend fun discardQuarantined(id: String) =
        onScope {
            store.removeQuarantined(id)
            refresh()
        }

    private suspend fun <T> onScope(block: suspend () -> T): T = scope.async { block() }.await()

    private suspend fun refresh() {
        refreshes.withLock {
            _entries.value = read()
            _quarantined.value = store.quarantined()
        }
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
