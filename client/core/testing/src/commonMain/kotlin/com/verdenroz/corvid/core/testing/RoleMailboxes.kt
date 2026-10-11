package com.verdenroz.corvid.core.testing

/** Adds one mailbox per role, its id the role itself and its name the role capitalised. */
fun FakeJmapServer.withRoleMailboxes(vararg roles: String): FakeJmapServer {
    roles.forEach { mailboxes += FakeMailbox(id = it, name = it.replaceFirstChar(Char::uppercase), role = it) }
    return this
}

/** The mailboxes message [id] is filed in now. */
fun FakeJmapServer.boxesOf(id: String): Set<String> = emails.single { it.id == id }.mailboxIds
