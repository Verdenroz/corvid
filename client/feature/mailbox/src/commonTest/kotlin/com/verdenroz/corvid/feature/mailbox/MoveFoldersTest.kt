package com.verdenroz.corvid.feature.mailbox

import com.verdenroz.corvid.core.model.Mailbox
import kotlin.test.Test
import kotlin.test.assertEquals

private fun box(
    id: String,
    name: String,
    role: String? = null,
    parentId: String? = null,
) = Mailbox(id, name, role, parentId, totalEmails = 0, unreadEmails = 0)

class MoveFoldersTest {
    @Test
    fun `role mailboxes come first in a fixed order, then the user's folders by path, and drafts is left out`() {
        val folders =
            moveFolders(
                listOf(
                    box("t", "Trash", "trash"),
                    box("w", "work"),
                    box("d", "Drafts", "drafts"),
                    box("i", "Inbox", "inbox"),
                    box("r", "Receipts"),
                    box("j", "Junk", "junk"),
                    box("a", "Archive", "archive"),
                ),
            )

        assertEquals(listOf("Inbox", "Archive", "Junk", "Trash", "Receipts", "work"), folders.map { it.label })
    }

    @Test
    fun `a nested folder is labelled with its path so same-named children stay apart`() {
        val folders = moveFolders(listOf(box("p", "Clients"), box("c", "2024", parentId = "p"), box("o", "2024")))

        assertEquals(listOf("2024", "Clients", "Clients / 2024"), folders.map { it.label })
    }
}
