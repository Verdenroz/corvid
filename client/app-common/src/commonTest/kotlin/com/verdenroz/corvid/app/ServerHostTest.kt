package com.verdenroz.corvid.app

import kotlin.test.Test
import kotlin.test.assertEquals

class ServerHostTest {
    @Test
    fun `the short host is the hostname's first label, without scheme, port or path`() {
        assertEquals("n1", shortHost("https://n1.starkmeshlark.test:4443"))
        assertEquals("mail", shortHost("https://mail.corvid.test/"))
        assertEquals("localhost", shortHost("http://localhost:8080"))
    }
}
