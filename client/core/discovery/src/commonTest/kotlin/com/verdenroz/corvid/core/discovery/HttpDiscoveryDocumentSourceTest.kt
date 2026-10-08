package com.verdenroz.corvid.core.discovery

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HttpDiscoveryDocumentSourceTest {
    private fun source(handler: MockEngine) = HttpDiscoveryDocumentSource(HttpClient(handler))

    @Test
    fun `a 200 answers with its body`() =
        runTest {
            val engine = MockEngine { respond("""{"version":1}""") }

            assertEquals("""{"version":1}""", source(engine).fetch("https://example.com/.well-known/corvid"))
        }

    @Test
    fun `any other status answers nothing`() =
        runTest {
            val engine = MockEngine { respondError(HttpStatusCode.NotFound) }

            assertNull(source(engine).fetch("https://example.com/.well-known/corvid"))
        }

    @Test
    fun `a connection failure answers nothing`() =
        runTest {
            val engine = MockEngine { throw IOException("refused") }

            assertNull(source(engine).fetch("https://example.com/.well-known/corvid"))
        }
}
