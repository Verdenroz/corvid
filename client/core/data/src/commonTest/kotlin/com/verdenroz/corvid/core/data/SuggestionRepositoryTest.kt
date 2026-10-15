package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.jmap.JmapError
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.model.RuleSuggestion
import com.verdenroz.corvid.core.model.SuggestionAction
import com.verdenroz.corvid.core.model.SuggestionSender
import com.verdenroz.corvid.core.model.SuggestionSenderKind
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.FakeSuggestion
import com.verdenroz.corvid.core.testing.client
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

private suspend fun repository(fake: FakeJmapServer) = SuggestionRepository(fake.client())

class SuggestionRepositoryTest {
    @Test
    fun `a suggestion is read field for field`() =
        runTest {
            val fake =
                FakeJmapServer().apply {
                    suggestions.serve(FakeSuggestion("s1", "list", "news.x.test", "folder", "m7", settled = 9, hits = 10))
                }

            assertEquals(
                listOf(
                    RuleSuggestion(
                        "s1",
                        SuggestionSender(SuggestionSenderKind.List, "news.x.test"),
                        SuggestionAction.Folder("m7"),
                        9,
                        10,
                    ),
                ),
                repository(fake).list(),
            )
        }

    @Test
    fun `an archive suggestion is read as archive`() =
        runTest {
            val fake = FakeJmapServer().apply { suggestions.serve(FakeSuggestion("s1")) }

            assertEquals(SuggestionAction.Archive, repository(fake).list().single().action)
        }

    @Test
    fun `a server without the route has no suggestions`() =
        runTest {
            assertEquals(emptyList(), repository(FakeJmapServer()).list())
        }

    @Test
    fun `a server with suggestions off has no suggestions`() =
        runTest {
            val fake = FakeJmapServer().apply { suggestions.serve(FakeSuggestion("s1")) }
            fake.suggestions.off = true

            assertEquals(emptyList(), repository(fake).list())
        }

    @Test
    fun `a suggestion of a kind the client has never seen is skipped while the others are shown`() =
        runTest {
            val fake =
                FakeJmapServer().apply {
                    suggestions.serve(
                        listOf(
                            FakeSuggestion("s1", actionKind = "shred"),
                            FakeSuggestion("s2", senderKind = "domain"),
                            FakeSuggestion("s3"),
                        ),
                    )
                }

            assertEquals(listOf("s3"), repository(fake).list().map { it.id })
        }

    @Test
    fun `a suggestion missing its fields is refused loudly`() =
        runTest {
            val fake = FakeJmapServer().apply { suggestions.serve(FakeSuggestion("s1")) }
            fake.suggestions.malformed = true

            val failure = assertFailsWith<JmapException> { repository(fake).list() }

            assertIs<JmapError.Request>(failure.error)
        }

    @Test
    fun `accepting and dismissing post to the suggestion`() =
        runTest {
            val fake = FakeJmapServer().apply { suggestions.serve(FakeSuggestion("s1"), FakeSuggestion("s2")) }
            val repository = repository(fake)

            repository.accept("s1")
            repository.dismiss("s2")

            assertEquals(listOf("s1"), fake.suggestions.accepted)
            assertEquals(listOf("s2"), fake.suggestions.dismissed)
        }

    @Test
    fun `an unknown id is not a failure`() =
        runTest {
            val fake = FakeJmapServer().apply { suggestions.serve(emptyList()) }

            repository(fake).accept("gone")

            assertEquals(emptyList(), fake.suggestions.accepted)
        }
}
