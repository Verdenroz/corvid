package com.verdenroz.corvid.core.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun provenance(seal: Seal) = Provenance(seal, rewrite = null, sender = SenderKind.Person, unsubscribe = emptyList())

class ProvenanceTest {
    @Test
    fun `web and mail links open from a message nobody flagged`() {
        val unsigned = provenance(Seal.Unsigned)

        assertTrue(unsigned.canOpen("https://x.test/a"))
        assertTrue(unsigned.canOpen("HTTP://x.test/a"))
        assertTrue(unsigned.canOpen("mailto:a@x.test"))
    }

    @Test
    fun `script, file and schemeless links never open`() {
        val signed = provenance(Seal.Signed("x.test"))

        assertFalse(signed.canOpen("javascript:alert(1)"))
        assertFalse(signed.canOpen("file:///etc/passwd"))
        assertFalse(signed.canOpen("intent://x#Intent;end"))
        assertFalse(signed.canOpen("x.test/a"))
    }

    @Test
    fun `nothing opens from a message whose seal failed`() {
        val failed = provenance(Seal.Failed("paypal.test", "paypa1.test"))

        assertTrue(failed.suspect)
        assertFalse(failed.canOpen("https://paypal.test/login"))
        assertFalse(failed.canOpen("mailto:a@paypal.test"))
    }
}
