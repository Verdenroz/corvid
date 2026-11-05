package com.verdenroz.corvid.app

import com.verdenroz.corvid.core.data.outbox.Contents
import com.verdenroz.corvid.core.data.outbox.OutboxEntry
import com.verdenroz.corvid.core.data.outbox.OutboxItem
import com.verdenroz.corvid.core.data.outbox.OutboxState
import com.verdenroz.corvid.core.data.outbox.RejectionCategory
import com.verdenroz.corvid.core.data.outbox.Tidy
import com.verdenroz.corvid.core.model.Draft
import kotlin.test.Test
import kotlin.test.assertEquals

private fun entry(
    state: OutboxState,
    contents: Contents? = Contents.Readable(Draft()),
) = OutboxEntry(OutboxItem("id", 1, 0, "fp", state), contents, result = null)

class OutboxPendingTest {
    @Test
    fun `an empty outbox counts nothing`() {
        assertEquals(OutboxPending(0, needsDecision = false), OutboxPending.of(emptyList()))
    }

    @Test
    fun `queued and sending messages count and wait on the network only`() {
        val entries = listOf(entry(OutboxState.Queued), entry(OutboxState.Submitting(1)))

        assertEquals(OutboxPending(2, needsDecision = false), OutboxPending.of(entries))
    }

    @Test
    fun `an unconfirmed message counts and needs a decision`() {
        assertEquals(
            OutboxPending(2, needsDecision = true),
            OutboxPending.of(listOf(entry(OutboxState.Queued), entry(OutboxState.Unconfirmed))),
        )
    }

    @Test
    fun `a rejected message counts and needs a decision`() {
        val rejected = entry(OutboxState.Rejected(RejectionCategory.Refused))

        assertEquals(OutboxPending(1, needsDecision = true), OutboxPending.of(listOf(rejected)))
    }

    @Test
    fun `a message that cannot be opened counts and needs a decision`() {
        val sealed = entry(OutboxState.Queued, Contents.SealedToOtherKey)

        assertEquals(OutboxPending(1, needsDecision = true), OutboxPending.of(listOf(sealed)))
    }

    @Test
    fun `a quarantined file counts and needs a decision`() {
        assertEquals(OutboxPending(1, needsDecision = true), OutboxPending.of(emptyList(), quarantined = 1))
    }

    @Test
    fun `a sent message is not pending`() {
        val sent = entry(OutboxState.Sent("e1", 1, Tidy()), contents = null)

        assertEquals(OutboxPending(0, needsDecision = false), OutboxPending.of(listOf(sent)))
    }
}
