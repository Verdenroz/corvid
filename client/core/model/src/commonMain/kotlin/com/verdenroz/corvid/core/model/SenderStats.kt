package com.verdenroz.corvid.core.model

import kotlin.time.Instant

/** Counted over every message whose address matches exactly, never sampled. */
data class SenderCounts(
    val received: Int,
    val firstSeen: Instant?,
    val sent: Int,
)

/**
 * Facts read from the newest [size] messages that match exactly, so each is exact over a stated
 * window. [marked] of them carried one readable tracker header, and [trackersStripped] covers those.
 */
data class SenderWindow(
    val size: Int,
    val signed: Int,
    val marked: Int,
    val trackersStripped: Int,
)
