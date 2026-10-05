package com.verdenroz.corvid.core.model

/**
 * One ledger row: [latest] is the newest message the list matched, [emailIds] every message in its thread, oldest first.
 * [unreadIds] are the thread's messages without `$seen`, wherever they are filed.
 */
data class Conversation(
    val latest: EmailSummary,
    val emailIds: List<String>,
    val unreadIds: Set<String>,
) {
    val messageCount: Int get() = emailIds.size
    val unread: Boolean get() = unreadIds.isNotEmpty()
}
