package com.verdenroz.corvid.core.testing

data class FakeMailbox(
    val id: String,
    val name: String,
    val role: String? = null,
    val parentId: String? = null,
    val totalEmails: Int = 0,
    val unreadEmails: Int = 0,
)

data class FakeEmail(
    val id: String,
    val threadId: String,
    val mailboxIds: Set<String>,
    val subject: String?,
    val from: String?,
    val receivedAt: String,
    val keywords: Set<String> = emptySet(),
    val preview: String? = "",
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val replyTo: List<String> = emptyList(),
    val fromName: String? = null,
    val messageId: List<String> = emptyList(),
    val inReplyTo: List<String> = emptyList(),
    val references: List<String> = emptyList(),
    val bodyType: String = "text/plain",
    val body: String? = null,
    val bodyTruncated: Boolean = false,
    /** A text/html alternative beside [body], as multipart/alternative carries it. */
    val htmlBody: String? = null,
    /** Raw header values by field name, in the order they appear in the message. */
    val headers: Map<String, List<String>> = emptyMap(),
)

data class FakeIdentity(
    val id: String,
    val email: String,
    val name: String = "",
)

data class FakeSubmission(
    val id: String,
    val identityId: String,
    val emailId: String,
)
