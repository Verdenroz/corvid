package com.verdenroz.corvid.core.data

/**
 * Each message's mailboxes [before] and [after] a move, so an undo puts back exactly what was there.
 * [refused] maps the ids the server would not move to its reason; those kept their mailboxes.
 */
data class MailMove(
    val before: Map<String, Set<String>>,
    val after: Map<String, Set<String>>,
    val refused: Map<String, String> = emptyMap(),
) {
    val moved: Set<String> get() = before.keys - refused.keys

    companion object {
        val NONE = MailMove(emptyMap(), emptyMap())
    }
}

/** Removes only the mailboxes a message left and adds only the ones it joined, as one JSON-pointer patch per message. */
internal fun mailboxPatch(
    before: Set<String>,
    after: Set<String>,
): Map<String, Boolean> = (before - after).associateWith { false } + (after - before).associateWith { true }

/** [ids] leaving the mailboxes they were in: [done] is false as the move starts and true once it has ended. */
data class MoveEvent(val ids: Set<String>, val done: Boolean)

/** What emptying a mailbox did: how many messages went for good, and the server's reason for each it kept. */
data class Emptied(val destroyed: Int, val refused: Map<String, String>)
