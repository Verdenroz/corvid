package com.verdenroz.corvid.core.model

import kotlin.time.Instant

/**
 * messageId and references are the original's `Message-ID` and `References`, what a reply threads on.
 * replyTo is empty unless the sender set a `Reply-To`.
 * bodyTruncated reflects the server's own isTruncated flag at maxBodyValueBytes, not a client guess.
 * html is the message's HTML part as sent, null when it has none; body is always the plain-text form.
 */
data class EmailDetail(
    val id: String,
    val subject: String,
    val from: EmailAddress?,
    val to: List<EmailAddress>,
    val cc: List<EmailAddress>,
    val replyTo: List<EmailAddress>,
    val receivedAt: Instant,
    val messageId: List<String>,
    val references: List<String>,
    val body: String,
    val bodyTruncated: Boolean,
    val html: HtmlBody?,
    val keywords: Set<String>,
    val provenance: Provenance,
)

/** [truncated] is the server's own flag, as for [EmailDetail.bodyTruncated]. */
data class HtmlBody(val html: String, val truncated: Boolean)
