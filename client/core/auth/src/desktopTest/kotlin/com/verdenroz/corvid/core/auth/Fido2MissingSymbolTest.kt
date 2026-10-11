package com.verdenroz.corvid.core.auth

import com.sun.jna.Library
import com.sun.jna.Native
import kotlin.test.Test
import kotlin.test.assertFailsWith

internal interface WithAbsentFunction : Library {
    fun corvidAbsent(): Int
}

class Fido2MissingSymbolTest {
    @Test
    fun `an interface that names a function libfido2 lacks loads and fails only when it is called`() {
        if (Fido2Library.load() == null) {
            println("skipped: libfido2 is not installed here")
            return
        }

        val library = Native.load("fido2", WithAbsentFunction::class.java, Fido2Library.OPTIONS)

        assertFailsWith<UnsatisfiedLinkError> { library.corvidAbsent() }
    }
}
