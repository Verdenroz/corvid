package com.verdenroz.corvid.core.data.outbox

/** What an unlock has to send with: the outbox, or why there is none. */
sealed interface OutboxAvailability {
    class Available(val outbox: Outbox) : OutboxAvailability

    /** Another process holds the outbox directory. */
    data object HeldElsewhere : OutboxAvailability

    /** The directory or the seal key could not be set up. */
    data object Failed : OutboxAvailability
}
