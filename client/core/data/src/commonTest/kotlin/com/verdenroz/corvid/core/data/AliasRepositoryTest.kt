package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.jmap.AuthLatch
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.createJmapHttpClient
import com.verdenroz.corvid.core.testing.FakeAlias
import com.verdenroz.corvid.core.testing.FakeJmapServer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.time.Instant

private const val BASE_URL = "http://127.0.0.1:9999"

class AliasRepositoryTest {
    private val fake = FakeJmapServer()
    private val latch = AuthLatch()

    private suspend fun repository() =
        AliasRepository(JmapClient.connect(createJmapHttpClient(fake.engine), latch, BASE_URL, bearerToken = { "daily" }))

    @Test
    fun `the list reads each alias field for field in the server's order`() =
        runTest {
            fake.aliases.aliases += FakeAlias("shop.abcd2345", createdAt = "2030-01-03T10:00:00Z")
            fake.aliases.aliases += FakeAlias("news.wxyz6789", enabled = false, createdAt = null)

            assertEquals(
                AliasListing.Listed(
                    listOf(
                        Alias("shop.abcd2345", "shop.abcd2345@corvid.test", "shop", true, Instant.parse("2030-01-03T10:00:00Z"), false),
                        Alias("news.wxyz6789", "news.wxyz6789@corvid.test", "news", false, null, false),
                    ),
                ),
                repository().list(),
            )
        }

    @Test
    fun `an alias a deploy wiped is listed as missing and disabled`() =
        runTest {
            fake.aliases.aliases += FakeAlias("gone.abcd2345", enabled = false, missing = true)

            val listing = repository().list() as AliasListing.Listed

            assertEquals(listOf(true), listing.aliases.map { it.missing })
            assertEquals(listOf(false), listing.aliases.map { it.enabled })
        }

    @Test
    fun `a server with aliases off lists nothing and says so`() =
        runTest {
            fake.aliases.off = true

            assertEquals(AliasRefusal.Off, repository().list())
        }

    @Test
    fun `an empty 503 is a busy mail server, not aliases switched off`() =
        runTest {
            fake.aliases.busy = true

            assertEquals(AliasRefusal.Busy, repository().list())
            assertEquals(AliasRefusal.Busy, repository().mint("shop"))
            assertEquals(AliasRefusal.Busy, repository().disable("shop.abcd2345", "master-token"))
        }

    @Test
    fun `a mail server that refused or failed is reported on every route`() =
        runTest {
            fake.aliases.failing = true

            assertEquals(AliasRefusal.Failed, repository().list())
            assertEquals(AliasRefusal.Failed, repository().mint("shop"))
            assertEquals(AliasRefusal.Failed, repository().enable("shop.abcd2345", "master-token"))
        }

    @Test
    fun `an alias is minted from a label with no admin token and comes back as the server made it`() =
        runTest {
            val outcome = repository().mint("shop")

            val minted = Alias("shop.abcd2345", "shop.abcd2345@corvid.test", "shop", true, Instant.parse("2030-01-02T00:00:00Z"), false)
            assertEquals(MintOutcome.Minted(minted), outcome)
            assertEquals(listOf("shop"), fake.aliases.labels)
            assertEquals(emptyList(), fake.aliases.masters)
        }

    @Test
    fun `a label the server refuses mints nothing`() =
        runTest {
            assertEquals(MintOutcome.BadLabel, repository().mint("no spaces"))

            assertEquals(emptyList(), fake.aliases.aliases)
        }

    @Test
    fun `a mint past the server's cap is reported as the cap`() =
        runTest {
            fake.aliases.atCap = true

            assertEquals(AliasCap, repository().mint("shop"))
        }

    @Test
    fun `an alias is disabled with the admin token on one header`() =
        runTest {
            fake.aliases.aliases += FakeAlias("shop.abcd2345")

            val outcome = repository().disable("shop.abcd2345", "master-token")

            assertEquals(false, (outcome as SwitchOutcome.Switched).alias.enabled)
            assertEquals(listOf(false), fake.aliases.aliases.map { it.enabled })
            assertEquals(listOf<String?>("master-token"), fake.aliases.masters)
        }

    @Test
    fun `a disabled alias is enabled again with the admin token`() =
        runTest {
            fake.aliases.aliases += FakeAlias("shop.abcd2345", enabled = false)

            val outcome = repository().enable("shop.abcd2345", "master-token")

            assertEquals(true, (outcome as SwitchOutcome.Switched).alias.enabled)
            assertEquals(listOf(true), fake.aliases.aliases.map { it.enabled })
        }

    @Test
    fun `enabling past the server's cap is reported as the cap and changes nothing`() =
        runTest {
            fake.aliases.aliases += FakeAlias("shop.abcd2345", enabled = false)
            fake.aliases.atCap = true

            assertEquals(AliasCap, repository().enable("shop.abcd2345", "master-token"))

            assertEquals(listOf(false), fake.aliases.aliases.map { it.enabled })
        }

    @Test
    fun `an alias the server does not hold is gone`() =
        runTest {
            fake.aliases.aliases += FakeAlias("wiped.abcd2345", enabled = false, missing = true)

            assertEquals(SwitchOutcome.Gone, repository().disable("nope.abcd2345", "master-token"))
            assertEquals(SwitchOutcome.Gone, repository().enable("wiped.abcd2345", "master-token"))
        }

    @Test
    fun `a switch with an admin token the server does not accept changes nothing and does not trip the latch`() =
        runTest {
            fake.aliases.aliases += FakeAlias("shop.abcd2345")

            assertEquals(AliasRefusal.NotAccepted, repository().disable("shop.abcd2345", "stale"))

            assertEquals(listOf(true), fake.aliases.aliases.map { it.enabled })
            assertFalse(latch.tripped.value)
        }

    @Test
    fun `a daily session the sentinel refuses on the list or a mint does not trip the latch`() =
        runTest {
            fake.aliases.dailyRefused = true

            assertEquals(AliasRefusal.NotAccepted, repository().list())
            assertEquals(AliasRefusal.NotAccepted, repository().mint("shop"))

            assertFalse(latch.tripped.value)
        }

    @Test
    fun `a web tier without the alias routes reads as aliases off on the list and a mint`() =
        runTest {
            fake.aliases.unrouted = true

            assertEquals(AliasRefusal.Off, repository().list())
            assertEquals(AliasRefusal.Off, repository().mint("shop"))
        }

    @Test
    fun `a server with aliases off is reported on a mint and a switch`() =
        runTest {
            fake.aliases.off = true

            assertEquals(AliasRefusal.Off, repository().mint("shop"))
            assertEquals(AliasRefusal.Off, repository().disable("shop.abcd2345", "master-token"))
        }

    @Test
    fun `a minted alias has an identity, disabling drops it and enabling again gives a new one`() =
        runTest {
            val aliases = repository()
            aliases.mint("shop")
            val minted = fake.identities.single().id

            aliases.disable("shop.abcd2345", "master-token")
            assertEquals(emptyList(), fake.identities)

            aliases.enable("shop.abcd2345", "master-token")
            assertEquals(listOf("shop.abcd2345@corvid.test"), fake.identities.map { it.email })
            assertNotEquals(minted, fake.identities.single().id)
        }
}
