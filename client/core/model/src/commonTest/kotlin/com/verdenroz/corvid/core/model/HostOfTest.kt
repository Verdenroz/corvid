package com.verdenroz.corvid.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class HostOfTest {
    @Test
    fun `the host is a url's hostname without scheme, port or path`() {
        assertEquals("n1.example.test", hostOf("https://n1.example.test:4443"))
        assertEquals("mail.example.test", hostOf("https://mail.example.test/jmap/session"))
        assertEquals("localhost", hostOf("http://localhost:8080"))
    }
}
