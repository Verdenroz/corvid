package com.verdenroz.corvid.core.data.outbox

import com.verdenroz.corvid.core.model.CorvidKeywords
import com.verdenroz.corvid.core.model.Draft
import java.util.UUID
import kotlin.time.Clock

/** Seals a draft to [key] and files it in the outbox; [domain] is what the message's own id is minted under. */
class OutboxQueue(
    private val store: OutboxStore,
    private val key: SealKey,
    private val domain: String,
    private val clock: Clock = Clock.System,
) {
    fun enqueue(
        draft: Draft,
        replacesDraftId: String?,
    ): String {
        val id = UUID.randomUUID().toString()
        val now = clock.now().toEpochMilliseconds()
        val message = OutboxMessage(draft, replacesDraftId, "$id@$domain", CorvidKeywords.OUTBOX + id, now)
        store.enqueue(id, OutboxSealer.seal(id, message, key), now)
        return id
    }
}
