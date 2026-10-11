package com.verdenroz.corvid.core.model

/** inReplyTo and references are Message-IDs, empty for a new message; attachments are blobs already on the server. */
data class Draft(
    val to: List<EmailAddress> = emptyList(),
    val cc: List<EmailAddress> = emptyList(),
    val bcc: List<EmailAddress> = emptyList(),
    val subject: String = "",
    val body: String = "",
    val inReplyTo: List<String> = emptyList(),
    val references: List<String> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
)
