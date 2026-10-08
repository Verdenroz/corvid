package com.verdenroz.corvid.core.domain

import com.verdenroz.corvid.core.model.ServerAddress
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private const val DOCUMENT = """{"version":1,"server":"https://mail.example.com:4443","issuer":"https://idp.example.com:4443"}"""

private class Rig(
    srv: Map<String, SrvTarget> = emptyMap(),
    private val documents: Map<String, String> = emptyMap(),
) {
    val fetched = mutableListOf<String>()
    val discover =
        DiscoverAccount(
            srvLookup = { srv[it] },
            documents =
                DiscoveryDocumentSource {
                    fetched += it
                    documents[it]
                },
        )
}

private const val FALLBACK_URL = "https://example.com/.well-known/corvid"

private fun serving(body: String) = Rig(documents = mapOf(FALLBACK_URL to body))

class DiscoverAccountTest {
    @Test
    fun `an SRV record sends the fetch to its target and port`() =
        runTest {
            val rig =
                Rig(
                    srv = mapOf("_corvid._tcp.example.com" to SrvTarget("mail.example.com.", 4443)),
                    documents = mapOf("https://mail.example.com:4443/.well-known/corvid" to DOCUMENT),
                )

            val found = rig.discover("mailuser@example.com")

            assertEquals(Discovery(ServerAddress("https://mail.example.com:4443"), "https://idp.example.com:4443"), found)
            assertEquals("mail.example.com", found.serverHost)
            assertEquals("idp.example.com", found.issuerHost)
        }

    @Test
    fun `no SRV record falls back to the domain on 443`() =
        runTest {
            val rig = serving(DOCUMENT)

            rig.discover("mailuser@example.com")

            assertEquals(listOf(FALLBACK_URL), rig.fetched)
        }

    @Test
    fun `the domain is whatever follows the last at sign`() =
        runTest {
            val rig = serving(DOCUMENT)

            rig.discover("odd@name@Example.com")

            assertEquals(listOf(FALLBACK_URL), rig.fetched)
        }

    @Test
    fun `an SRV record whose document is unreachable does not fall back to 443`() =
        runTest {
            val rig =
                Rig(
                    srv = mapOf("_corvid._tcp.example.com" to SrvTarget("mail.example.com", 4443)),
                    documents = mapOf("https://example.com/.well-known/corvid" to DOCUMENT),
                )

            assertFailsWith<DiscoveryFailure.Unreachable> { rig.discover("mailuser@example.com") }
            assertEquals(listOf("https://mail.example.com:4443/.well-known/corvid"), rig.fetched)
        }

    @Test
    fun `an address with no domain is refused before any lookup`() =
        runTest {
            val rig = Rig()

            assertFailsWith<DiscoveryFailure.NoDomain> { rig.discover("mailuser") }
            assertFailsWith<DiscoveryFailure.NoDomain> { rig.discover("mailuser@") }
            assertEquals(emptyList(), rig.fetched)
        }

    @Test
    fun `a document that is not JSON is malformed`() =
        runTest {
            val rig = serving("<html>")

            assertFailsWith<DiscoveryFailure.Malformed> { rig.discover("mailuser@example.com") }
        }

    @Test
    fun `a document missing the issuer is malformed`() =
        runTest {
            val rig = serving("""{"version":1,"server":"https://mail.example.com"}""")

            assertFailsWith<DiscoveryFailure.Malformed> { rig.discover("mailuser@example.com") }
        }

    @Test
    fun `a document of another version is refused`() =
        runTest {
            val rig = serving(DOCUMENT.replace("\"version\":1", "\"version\":2"))

            assertFailsWith<DiscoveryFailure.UnsupportedVersion> { rig.discover("mailuser@example.com") }
        }

    @Test
    fun `an http issuer is refused`() =
        runTest {
            val rig = serving(DOCUMENT.replace("https://idp", "http://idp"))

            assertFailsWith<DiscoveryFailure.NotHttps> { rig.discover("mailuser@example.com") }
        }

    @Test
    fun `an http server is refused`() =
        runTest {
            val rig = serving(DOCUMENT.replace("https://mail", "http://mail"))

            assertFailsWith<DiscoveryFailure.NotHttps> { rig.discover("mailuser@example.com") }
        }
}
