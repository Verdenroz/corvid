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
    val preview: String = "",
)
