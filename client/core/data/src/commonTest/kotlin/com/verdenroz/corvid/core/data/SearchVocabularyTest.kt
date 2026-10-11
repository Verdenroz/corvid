package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.testing.FakeEmail
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.client
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun server() =
    FakeJmapServer().apply {
        emails +=
            FakeEmail(
                "m1",
                "m1",
                setOf("inbox"),
                "Your invoice is ready",
                "greenpieguy@gmail.com",
                "2024-01-01T00:00:00Z",
                fromName = "Pie Guy",
            )
                .copy(preview = "Invoices attached for the quarter")
        emails +=
            FakeEmail("m2", "m2", setOf("sent"), "Lunch", "me@example.test", "2024-01-02T00:00:00Z", to = listOf("Ada@Analytical.org"))
                .copy(preview = "Invoice paid")
    }

private suspend fun filter(text: String) = SearchVocabulary(MailSample(server().client()) { null }).completions(text)

private fun JsonObject.conditions() = getValue("conditions").jsonArray.map { it.jsonObject }

private fun JsonObject.leaves(): List<Pair<String, String>> =
    if ("operator" in this) conditions().flatMap { it.leaves() } else entries.map { (k, v) -> k to v.jsonPrimitive.content }

class SearchVocabularyTest {
    @Test
    fun `a fragment of a known address searches that whole address as sender and recipient`() =
        runTest {
            val leaves = filter("green").leaves()

            assertTrue("from" to "greenpieguy@gmail.com" in leaves)
            assertTrue("to" to "greenpieguy@gmail.com" in leaves)
        }

    @Test
    fun `a fragment of a display name finds the address behind it`() =
        runTest {
            assertTrue("from" to "greenpieguy@gmail.com" in filter("guy").leaves())
        }

    @Test
    fun `addresses seen only as recipients are known too, lowercased`() =
        runTest {
            assertTrue("to" to "ada@analytical.org" in filter("analytical").leaves())
        }

    @Test
    fun `the word being typed completes against subjects and body openings, commonest first`() =
        runTest {
            val leaves = filter("invo").leaves()

            assertEquals(listOf("invo", "invoice", "invoices"), leaves.filter { it.first == "text" }.map { it.second })
        }

    @Test
    fun `text matching nothing known is a plain word search`() =
        runTest {
            assertEquals(buildJsonObject { put("text", "zebra") }, filter("zebra"))
        }

    @Test
    fun `a single character is never expanded`() =
        runTest {
            assertEquals(buildJsonObject { put("text", "g") }, filter("g"))
        }
}
