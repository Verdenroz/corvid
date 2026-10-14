package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.jmap.JmapError
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.model.ThreadSummary
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.client
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

private suspend fun repository(fake: FakeJmapServer) = SummaryRepository(fake.client())

class SummaryRepositoryTest {
    @Test
    fun `a summary is read with its text and time`() =
        runTest {
            val fake = FakeJmapServer().apply { summaries.serve("t1", "They settled on Friday.", "2024-05-06T07:08:09Z") }

            assertEquals(
                ThreadSummary("They settled on Friday.", Instant.parse("2024-05-06T07:08:09Z")),
                repository(fake).get("t1"),
            )
        }

    @Test
    fun `a thread with no summary has none`() =
        runTest {
            val fake = FakeJmapServer().apply { summaries.serve("t1", "x") }

            assertNull(repository(fake).get("t2"))
        }

    @Test
    fun `a server with summaries off has none`() =
        runTest {
            val fake = FakeJmapServer().apply { summaries.serve("t1", "x") }
            fake.summaries.off = true

            assertNull(repository(fake).get("t1"))
        }

    @Test
    fun `a summary missing its text is refused loudly`() =
        runTest {
            val fake = FakeJmapServer().apply { summaries.serve("t1", "x") }
            fake.summaries.malformed = true

            val failure = assertFailsWith<JmapException> { repository(fake).get("t1") }

            assertIs<JmapError.Request>(failure.error)
        }
}
