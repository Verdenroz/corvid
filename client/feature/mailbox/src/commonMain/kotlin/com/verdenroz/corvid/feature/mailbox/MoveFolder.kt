package com.verdenroz.corvid.feature.mailbox

import com.verdenroz.corvid.core.data.ARCHIVE
import com.verdenroz.corvid.core.data.INBOX
import com.verdenroz.corvid.core.data.JUNK
import com.verdenroz.corvid.core.data.TRASH
import com.verdenroz.corvid.core.model.Mailbox
import com.verdenroz.corvid.core.model.refs

/** A mailbox a conversation can be moved to; [label] is its path from the root, so same-named children stay apart. */
data class MoveFolder(val id: String, val label: String)

private val ROLE_ORDER = listOf(INBOX, ARCHIVE, "sent", JUNK, TRASH)

/** Role mailboxes first in a fixed order, then the user's own folders by path; Drafts holds only unsent mail, so it is left out. */
fun moveFolders(mailboxes: List<Mailbox>): List<MoveFolder> {
    val roles = mailboxes.associate { it.id to it.role }
    val folders = mailboxes.refs().filter { roles[it.id] != "drafts" }.map { MoveFolder(it.id, it.path.joinToString(" / ")) }
    val (withRole, own) = folders.partition { roles[it.id] in ROLE_ORDER }
    return withRole.sortedBy { ROLE_ORDER.indexOf(roles[it.id]) } + own.sortedBy { it.label.lowercase() }
}
