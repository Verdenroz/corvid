package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.model.Seal
import kotlin.test.Test
import kotlin.test.assertEquals

private const val GENUINE_PASS = "mail.corvid.test; spf=pass smtp.mailfrom=news.example; dmarc=pass header.from=news.example"
private const val GENUINE_NONE = "mail.corvid.test; spf=none; dmarc=none header.from=evil.example"

// Same authserv-id as the genuine one: copying it is free.
private const val FORGED_PASS = "mail.corvid.test; dkim=pass; dmarc=pass header.from=trusted.example"

class AuthenticationResultsTest {
    @Test
    fun `a pass is read out of a full results header with its signer`() {
        assertEquals(Seal.Signed("news.example"), readSeal(listOf(GENUINE_PASS)))
    }

    @Test
    fun `a forged pass below our own verdict is ignored`() {
        assertEquals(Seal.Unsigned, readSeal(listOf(GENUINE_NONE, FORGED_PASS)))
    }

    @Test
    fun `a forged header cannot be smuggled in by repeating it`() {
        assertEquals(Seal.Unsigned, readSeal(listOf(GENUINE_NONE, FORGED_PASS, FORGED_PASS, FORGED_PASS)))
    }

    @Test
    fun `our own pass still counts with older verdicts below it`() {
        assertEquals(Seal.Signed("news.example"), readSeal(listOf(GENUINE_PASS, GENUINE_NONE)))
    }

    @Test
    fun `a dmarc failure is suspicious and names the domain it claimed and whoever did sign`() {
        val failed = "mail.corvid.test; dkim=pass header.d=paypa1-secure.test; dmarc=fail header.from=paypal.test"

        assertEquals(Seal.Failed(claimed = "paypal.test", signedBy = "paypa1-secure.test"), readSeal(listOf(failed)))
    }

    @Test
    fun `a dmarc failure with no passing dkim names no signer`() {
        assertEquals(
            Seal.Failed("news.example", null),
            readSeal(listOf("mail.corvid.test; dkim=fail; dmarc=fail header.from=news.example")),
        )
    }

    @Test
    fun `a none verdict is unsigned, not suspicious`() {
        assertEquals(Seal.Unsigned, readSeal(listOf(GENUINE_NONE)))
    }

    @Test
    fun `other methods passing is not dmarc passing`() {
        assertEquals(Seal.Unsigned, readSeal(listOf("mail.corvid.test; spf=pass; dkim=pass")))
    }

    @Test
    fun `a missing header is unsigned`() {
        assertEquals(Seal.Unsigned, readSeal(emptyList()))
        assertEquals(Seal.Unsigned, readSeal(listOf("")))
    }

    @Test
    fun `a value that merely starts with pass is not a pass`() {
        assertEquals(Seal.Unsigned, readSeal(listOf("mail.corvid.test; dmarc=passthrough")))
    }

    @Test
    fun `a pass with no header from is still signed, with the signer left unnamed`() {
        assertEquals(Seal.Signed(null), readSeal(listOf("mx; dmarc=pass")))
    }

    @Test
    fun `case is folded in methods, results and property names`() {
        assertEquals(Seal.Signed("x.test"), readSeal(listOf("mx; DMARC=PASS Header.From=x.test")))
    }

    @Test
    fun `a comment between the result and its properties does not hide the signer`() {
        assertEquals(Seal.Signed("x.test"), readSeal(listOf("mx; dmarc=pass (p=reject dis=none) header.from=x.test")))
    }
}
