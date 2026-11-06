package com.verdenroz.corvid.desktop

import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerProbeTest {
    @Test
    fun `it is true when the server accepts a connection`() =
        ServerSocket(0).use { server ->
            assertTrue(runBlocking { ServerProbe { "http://127.0.0.1:${server.localPort}" }() })
        }

    @Test
    fun `it is false when nothing listens at the server address`() {
        val port = ServerSocket(0).use { it.localPort }
        assertFalse(runBlocking { ServerProbe { "http://127.0.0.1:$port" }() })
    }

    @Test
    fun `it is true while there is no server to ask`() {
        assertTrue(runBlocking { ServerProbe { null }() })
    }

    @Test
    fun `a malformed server address reads as offline`() {
        assertFalse(runBlocking { ServerProbe { "http://[bad host:99999" }() })
        assertFalse(runBlocking { ServerProbe { "not a url" }() })
    }
}
