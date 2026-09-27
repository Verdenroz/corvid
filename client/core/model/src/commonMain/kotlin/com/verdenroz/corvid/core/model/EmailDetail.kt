package com.verdenroz.corvid.core.model

import kotlin.time.Instant

/** bodyTruncated reflects the server's own isTruncated flag at maxBodyValueBytes, not a client guess. */
data class EmailDetail(
    val id: String,
    val subject: String,
    val from: String,
    val to: List<String>,
    val cc: List<String>,
    val receivedAt: Instant,
    val body: String,
    val bodyTruncated: Boolean,
)
