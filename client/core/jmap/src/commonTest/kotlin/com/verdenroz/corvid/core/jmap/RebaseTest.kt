package com.verdenroz.corvid.core.jmap

import kotlin.test.Test
import kotlin.test.assertEquals

class RebaseTest {
    @Test
    fun `an advertised url moves onto the host that was dialled`() {
        assertEquals(
            "http://127.0.0.1:8080/jmap/",
            rebase("http://127.0.0.1:8080", "https://mail.example.test/jmap/"),
        )
    }

    @Test
    fun `a template keeps its placeholders`() {
        val advertised = "https://mail.example.test/jmap/download/{accountId}/{blobId}/{name}?accept={type}"

        assertEquals(
            "http://127.0.0.1:8080/jmap/download/{accountId}/{blobId}/{name}?accept={type}",
            rebase("http://127.0.0.1:8080", advertised),
        )
    }

    @Test
    fun `a port in either place is handled`() {
        assertEquals(
            "https://mail.example.test:4443/jmap/",
            rebase("https://mail.example.test:4443", "https://mail.example.test/jmap/"),
        )
        assertEquals(
            "http://127.0.0.1:8080/jmap/",
            rebase("http://127.0.0.1:8080", "https://mail.example.test:443/jmap/"),
        )
    }

    @Test
    fun `a trailing slash on the base does not double`() {
        assertEquals(
            "http://127.0.0.1:8080/jmap/",
            rebase("http://127.0.0.1:8080/", "https://mail.example.test/jmap/"),
        )
    }

    @Test
    fun `a bare authority becomes the root`() {
        assertEquals(
            "http://127.0.0.1:8080/",
            rebase("http://127.0.0.1:8080", "https://mail.example.test"),
        )
    }

    @Test
    fun `a relative url is joined as a path`() {
        assertEquals(
            "http://127.0.0.1:8080/jmap/",
            rebase("http://127.0.0.1:8080", "/jmap/"),
        )
    }
}
