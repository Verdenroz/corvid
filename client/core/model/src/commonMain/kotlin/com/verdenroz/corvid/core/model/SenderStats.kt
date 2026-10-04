package com.verdenroz.corvid.core.model

import kotlin.time.Instant

/**
 * Counted over every message whose address matches exactly, never sampled. [marked] is how many
 * carried a readable tracker header, so [trackersStripped] covers those and no others.
 */
data class SenderStats(
    val received: Int,
    val firstSeen: Instant?,
    val signed: Int,
    val marked: Int,
    val trackersStripped: Int,
    val sent: Int,
)
