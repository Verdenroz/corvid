package com.verdenroz.corvid.core.model

import kotlin.time.Instant

/** The server's digest of the mail since the last one. [DigestItem.summary] derives from mail, so it is untrusted. */
data class Digest(val id: String, val from: Instant, val to: Instant, val groups: List<DigestGroup>)

/** The items under one [label], or under none for the mail that fell in no group. */
data class DigestGroup(val label: String?, val items: List<DigestItem>)

data class DigestItem(
    val threadId: String,
    val emailId: String,
    val from: String,
    val subject: String,
    val receivedAt: Instant,
    val summary: String?,
)
