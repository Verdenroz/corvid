package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.jmap.AuthLatch
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.createJmapHttpClient
import com.verdenroz.corvid.core.model.Label
import com.verdenroz.corvid.core.testing.FakeEmail
import com.verdenroz.corvid.core.testing.FakeJmapServer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private val GUESS = Label.Guess("personal")

private fun fake() =
    FakeJmapServer().also {
        it.emails +=
            FakeEmail(
                "m1", "m1", setOf("inbox"), "s", "ada@x.test", "2026-10-04T02:28:00Z",
                keywords = setOf("corvid-llm-done", "corvid-llm-label-personal", "corvid-receipts", "\$seen"),
            )
    }

private suspend fun answer(
    fake: FakeJmapServer,
    correction: Correction,
): String? {
    val client = JmapClient.connect(createJmapHttpClient(fake.engine), AuthLatch(), "http://127.0.0.1:9999")
    return MailboxRepository(client).updateKeywords("m1", correction.keywordPatch())
}

class CorrectionTest {
    @Test
    fun `right adds the guess as the user's own keyword and removes the guess`() =
        runTest {
            val fake = fake()

            assertNull(answer(fake, Correction.Right(GUESS)))

            assertEquals(
                "m1" to JsonObject(mapOf("keywords/personal" to JsonPrimitive(true), "keywords/corvid-llm-label-personal" to JsonNull)),
                fake.emailPatches.single(),
            )
            assertEquals(setOf("corvid-llm-done", "corvid-receipts", "\$seen", "personal"), fake.emails.single().keywords)
        }

    @Test
    fun `wrong only removes the guess and keeps the model's done marker`() =
        runTest {
            val fake = fake()

            answer(fake, Correction.Wrong(GUESS))

            assertEquals("m1" to JsonObject(mapOf("keywords/corvid-llm-label-personal" to JsonNull)), fake.emailPatches.single())
            assertEquals(setOf("corvid-llm-done", "corvid-receipts", "\$seen"), fake.emails.single().keywords)
        }

    @Test
    fun `change adds the user's chosen keyword and removes the guess`() =
        runTest {
            val fake = fake()

            answer(fake, Correction.Change(GUESS, "work"))

            assertEquals(
                "m1" to JsonObject(mapOf("keywords/work" to JsonPrimitive(true), "keywords/corvid-llm-label-personal" to JsonNull)),
                fake.emailPatches.single(),
            )
        }

    @Test
    fun `a keyword holding a slash or tilde is escaped in the patch path`() =
        runTest {
            val fake = fake()

            answer(fake, Correction.Change(GUESS, "a/b~c"))

            assertEquals(setOf("keywords/a~1b~0c", "keywords/corvid-llm-label-personal"), fake.emailPatches.single().second.keys)
            assertEquals(true, "a/b~c" in fake.emails.single().keywords)
        }

    @Test
    fun `a message gone before the answer reports the server's refusal`() =
        runTest {
            val fake = fake()
            fake.emails.clear()

            assertEquals("no such email", answer(fake, Correction.Wrong(GUESS)))
        }
}
