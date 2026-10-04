package com.verdenroz.corvid.core.testing

/** Gives the account a Sieve script of its own that is already running, as a vacation responder would be. */
fun FakeJmapServer.withActiveScript(
    name: String = "vacation",
    id: String = "old",
): FakeJmapServer {
    sieveScripts += FakeSieveScript(id = id, name = name, blobId = "b0", isActive = true)
    return this
}
