package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.jmap.JmapError
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.testing.FakeDigest
import com.verdenroz.corvid.core.testing.FakeDigestItem
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.client
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

private suspend fun repository(fake: FakeJmapServer) = DigestRepository(fake.client())

private val NIGHT =
    FakeDigest(
        id = "d2",
        groups =
            listOf(
                "Work" to listOf(FakeDigestItem(threadId = "t1", emailId = "e1", summary = "Moved to Friday.")),
                null to listOf(FakeDigestItem(threadId = "t2", emailId = "e2", subject = "Hello")),
            ),
    )

class DigestRepositoryTest {
    @Test
    fun `the latest digest is read with its groups and items`() =
        runTest {
            val fake = FakeJmapServer().apply { digests.serve(FakeDigest("d1"), NIGHT) }

            val digest = repository(fake).latest()!!

            assertEquals("d2", digest.id)
            assertEquals(Instant.parse("2024-05-06T07:00:00Z"), digest.to)
            assertEquals(listOf("Work", null), digest.groups.map { it.label })
            assertEquals("Moved to Friday.", digest.groups[0].items[0].summary)
            assertNull(digest.groups[1].items[0].summary)
            assertEquals("Hello", digest.groups[1].items[0].subject)
        }

    @Test
    fun `an item carries who wrote it and whether a list sent it, and an older server has neither`() =
        runTest {
            val fake =
                FakeJmapServer().apply {
                    digests.serve(
                        FakeDigest(
                            "d1",
                            groups =
                                listOf(
                                    null to
                                        listOf(
                                            FakeDigestItem(emailId = "e1", fromName = "Ada", isList = true),
                                            FakeDigestItem(emailId = "e2"),
                                        ),
                                ),
                        ),
                    )
                }

            val items = repository(fake).latest()!!.groups.single().items

            assertEquals("Ada", items[0].fromName)
            assertEquals(true, items[0].isList)
            assertNull(items[1].fromName)
            assertEquals(false, items[1].isList)
        }

    @Test
    fun `a digest is read by its id`() =
        runTest {
            val fake = FakeJmapServer().apply { digests.serve(FakeDigest("d1"), NIGHT) }

            assertEquals("d1", repository(fake).get("d1")?.id)
            assertNull(repository(fake).get("d9"))
        }

    @Test
    fun `a server with no digest yet has none`() =
        runTest {
            val fake = FakeJmapServer()

            assertNull(repository(fake).latest())
        }

    @Test
    fun `a server with digests off has none`() =
        runTest {
            val fake = FakeJmapServer().apply { digests.serve(NIGHT) }
            fake.digests.off = true

            assertNull(repository(fake).latest())
        }

    @Test
    fun `a digest missing its groups is refused loudly`() =
        runTest {
            val fake = FakeJmapServer().apply { digests.serve(NIGHT) }
            fake.digests.malformed = true

            val failure = assertFailsWith<JmapException> { repository(fake).latest() }

            assertIs<JmapError.Request>(failure.error)
        }
}
