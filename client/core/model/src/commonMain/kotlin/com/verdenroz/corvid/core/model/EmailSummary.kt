package com.verdenroz.corvid.core.model

import kotlin.time.Instant

/** [keywords] mixes the user's own with Corvid's `corvid-` ones; [CorvidKeywords] tells them apart. */
data class EmailSummary(
    val id: String,
    val threadId: String,
    val subject: String,
    val from: EmailAddress?,
    val receivedAt: Instant,
    val keywords: Set<String>,
    val preview: String,
    val provenance: Provenance,
)
