package com.verdenroz.corvid.core.model

data class Mailbox(
    val id: String,
    val name: String,
    val role: String?,
    val parentId: String?,
    val totalEmails: Int,
    val unreadEmails: Int,
)

/** A folder as a path from the root, so same-named children stay distinct and no name is ever split on a delimiter. */
data class MailboxRef(
    val id: String,
    val path: List<String>,
) {
    companion object {
        val None = MailboxRef("", emptyList())
    }
}

fun List<Mailbox>.refs(): List<MailboxRef> {
    val byId = associateBy { it.id }

    fun pathOf(mailbox: Mailbox): List<String> = mailbox.parentId?.let { byId[it] }?.let { pathOf(it) }.orEmpty() + mailbox.name
    return map { MailboxRef(it.id, pathOf(it)) }
}
