package com.verdenroz.corvid.core.data

import kotlin.test.Test
import kotlin.test.assertEquals

class AddressesTest {
    @Test
    fun `a bare address list splits on commas and semicolons`() {
        assertEquals(
            listOf("a@example.test", "b@example.test", "c@example.test"),
            splitAddresses("a@example.test, b@example.test;c@example.test"),
        )
    }

    @Test
    fun `a comma inside a quoted name does not split the address`() {
        assertEquals(
            listOf("\"Doe, Jane\" <jane@example.test>", "bob@example.test"),
            splitAddresses("\"Doe, Jane\" <jane@example.test>, bob@example.test"),
        )
    }

    @Test
    fun `a name with a comma is quoted on format and recovered on parse`() {
        val formatted = formatAddress("Doe, Jane", "jane@example.test")

        assertEquals("\"Doe, Jane\" <jane@example.test>", formatted)
        assertEquals(listOf(formatted), splitAddresses(formatted))
        assertEquals("Doe, Jane", nameOf(formatted))
        assertEquals("jane@example.test", emailOf(formatted))
    }

    @Test
    fun `a plain name is left unquoted`() {
        assertEquals("Jane Doe <jane@example.test>", formatAddress("Jane Doe", "jane@example.test"))
    }
}
