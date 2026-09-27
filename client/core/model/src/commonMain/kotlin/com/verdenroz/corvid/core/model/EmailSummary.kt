package com.verdenroz.corvid.core.model

import kotlin.time.Instant

/** A label the labeler or a person set. Corvid's own writes are prefixed `corvid-`
 *  (design.md, "keyword provenance"); this model doesn't need to tell those apart, only show them. */
data class EmailSummary(
    val id: String,
    val threadId: String,
    val subject: String,
    val from: String,
    val receivedAt: Instant,
    val keywords: Set<String>,
    val preview: String,
)
