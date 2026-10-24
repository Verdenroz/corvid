package com.verdenroz.corvid.core.model

import kotlin.time.Instant

/**
 * messageId and references are the original's `Message-ID` and `References`, what a reply threads on.
 * replyTo is empty unless the sender set a `Reply-To`.
 * preview is the server's own short text of the body, for a collapsed message.
 * bodyTruncated reflects the server's own isTruncated flag at maxBodyValueBytes, not a client guess.
 * html is the message's HTML part as sent, null when it has none; body is always the plain-text form.
 * mailboxIds are the mailboxes it is filed in, which decide whether it can be archived or only deleted for good.
 * attachments are the files it carries, leaving out the inline images its HTML draws by `cid:`;
 * inlineImages are those, by content id in lower case, without its angle brackets.
 * arrivedVia is the alias the server's own Delivered-To names, null unless that is one of the account's current identities other than the main one.
 * deliveredTo is that Delivered-To as the server wrote it, an identity or not.
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
    val preview: String,
    val keywords: Set<String>,
    val provenance: Provenance,
    val mailboxIds: Set<String> = emptySet(),
    val attachments: List<Attachment> = emptyList(),
    val inlineImages: Map<String, Attachment> = emptyMap(),
    val arrivedVia: String? = null,
    val deliveredTo: String? = null,
)

/** [truncated] is the server's own flag, as for [EmailDetail.bodyTruncated]. */
data class HtmlBody(val html: String, val truncated: Boolean)
