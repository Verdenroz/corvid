package com.verdenroz.corvid.core.auth

import kotlin.test.Test

/**
 * Loads the real libfido2 and looks for a key without touching it. A machine without the library
 * (CI) has nothing to check and says so; one with a key attached also reads how the key verifies.
 */
class Fido2SmokeTest {
    @Test
    fun `the system libfido2 loads and enumerates keys without error`() {
        val lib = Fido2Library.load()
        if (lib == null) {
            println("skipped: libfido2 is not installed here")
            return
        }
        val key = Fido2SecurityKeys(lib).first()
        if (key == null) {
            println("no security key attached")
            return
        }
        key.use { println("security key verifies by ${it.verification}") }
    }
}
