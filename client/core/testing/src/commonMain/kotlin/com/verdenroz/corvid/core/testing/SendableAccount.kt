package com.verdenroz.corvid.core.testing

const val SENDABLE_ACCOUNT_EMAIL = "verde@example.test"

/** Gives the account what sending needs: Drafts and Sent mailboxes and one identity. */
fun FakeJmapServer.withSendableAccount(): FakeJmapServer {
    mailboxes += FakeMailbox(id = "drafts", name = "Drafts", role = "drafts")
    mailboxes += FakeMailbox(id = "sent", name = "Sent", role = "sent")
    identities += FakeIdentity(id = "i1", email = SENDABLE_ACCOUNT_EMAIL, name = "Verde")
    return this
}
