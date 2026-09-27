package com.verdenroz.corvid.core.model

data class Mailbox(
    val id: String,
    val name: String,
    val role: String?,
    val parentId: String?,
    val totalEmails: Int,
    val unreadEmails: Int,
)
