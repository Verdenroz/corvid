package com.verdenroz.corvid.core.data.outbox

import com.verdenroz.corvid.core.model.Draft

/** What the outbox can show of a message: its draft only while this device key can open it. */
sealed interface Contents {
    data class Readable(val draft: Draft) : Contents

    data object SealedToOtherKey : Contents

    data object Unreadable : Contents
}

/** [contents] is null for a sent message, which keeps no body. */
data class OutboxEntry(
    val item: OutboxItem,
    val contents: Contents?,
)
