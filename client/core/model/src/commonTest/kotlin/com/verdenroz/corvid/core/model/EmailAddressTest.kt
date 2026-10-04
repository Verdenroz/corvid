package com.verdenroz.corvid.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class EmailAddressTest {
    @Test
    fun `a bare address list splits on commas and semicolons`() {
        assertEquals(
            listOf("a@example.test", "b@example.test", "c@example.test").map { EmailAddress(null, it) },
            parseAddresses("a@example.test, b@example.test;c@example.test"),
        )
    }

    @Test
    fun `a comma inside a quoted name does not split the address`() {
        assertEquals(
            listOf(EmailAddress("Doe, Jane", "jane@example.test"), EmailAddress(null, "bob@example.test")),
            parseAddresses("\"Doe, Jane\" <jane@example.test>, bob@example.test"),
        )
    }

    @Test
    fun `a name with a comma is quoted as text and recovered on parse`() {
        val address = EmailAddress("Doe, Jane", "jane@example.test")

        assertEquals("\"Doe, Jane\" <jane@example.test>", address.toText())
        assertEquals(listOf(address), parseAddresses(address.toText()))
    }

    @Test
    fun `a plain name is left unquoted`() {
        assertEquals("Jane Doe <jane@example.test>", EmailAddress("Jane Doe", "jane@example.test").toText())
    }

    @Test
    fun `an address with no name is its bare email`() {
        assertEquals("jane@example.test", EmailAddress(null, "jane@example.test").toText())
    }
}
