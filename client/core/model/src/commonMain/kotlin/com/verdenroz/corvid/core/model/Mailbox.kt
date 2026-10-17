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
    fun displayPath(): String = path.joinToString(" / ")

    companion object {
        val None = MailboxRef("", emptyList())
    }
}

fun List<Mailbox>.refs(): List<MailboxRef> {
    val byId = associateBy { it.id }

    fun pathOf(mailbox: Mailbox): List<String> = mailbox.parentId?.let { byId[it] }?.let { pathOf(it) }.orEmpty() + mailbox.name
    return map { MailboxRef(it.id, pathOf(it)) }
}

/** The folder's path for display, or its id when it is not among these. */
fun List<MailboxRef>.folderPath(mailboxId: String): String = firstOrNull { it.id == mailboxId }?.displayPath() ?: mailboxId
