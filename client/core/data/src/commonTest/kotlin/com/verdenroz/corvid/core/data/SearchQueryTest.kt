package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.model.CorvidKeywords
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

private val NOW = Instant.parse("2026-10-10T12:00:30.500Z")
private val NEW_YORK = TimeZone.of("America/New_York")

private fun parse(
    text: String,
    zone: TimeZone = TimeZone.UTC,
) = SearchQuery.parse(text, NOW, zone)

private suspend fun filter(text: String): JsonObject? =
    parse(text).toFilter(
        mailboxId = { role -> mapOf(INBOX to "mb-inbox", TRASH to "mb-trash", JUNK to "mb-junk")[role] },
        complete = { buildJsonObject { put("completing", it) } },
    )

private fun condition(
    key: String,
    value: String,
) = buildJsonObject { put(key, value) }

private fun all(vararg conditions: JsonObject) =
    buildJsonObject {
        put("operator", "AND")
        putJsonArray("conditions") { conditions.forEach { add(it) } }
    }

private fun not(condition: JsonObject) =
    buildJsonObject {
        put("operator", "NOT")
        putJsonArray("conditions") { add(condition) }
    }

class SearchQueryTest {
    @Test
    fun `plain words stay whole except the last, which is completed as it is typed`() =
        runTest {
            assertEquals(all(condition("text", "paid"), condition("completing", "invo")), filter("paid invo"))
        }

    @Test
    fun `address and subject operators filter their own field`() =
        runTest {
            assertEquals(
                all(condition("from", "ada"), condition("to", "bob@x.test"), condition("cc", "eve"), condition("subject", "q3")),
                filter("from:ada to:bob@x.test cc:eve subject:q3"),
            )
        }

    @Test
    fun `an operator name is case-insensitive and its value keeps its case`() =
        runTest {
            assertEquals(condition("from", "Ada"), filter("FROM:Ada"))
        }

    @Test
    fun `a quoted operator value may hold spaces`() =
        runTest {
            assertEquals(condition("subject", "weekly report"), filter("subject:\"weekly report\""))
        }

    @Test
    fun `has attachment asks the server for messages with one`() =
        runTest {
            assertEquals(buildJsonObject { put("hasAttachment", true) }, filter("has:attachment"))
        }

    @Test
    fun `is unread, read and starred map to keywords present or absent`() =
        runTest {
            assertEquals(
                all(
                    condition("notKeyword", CorvidKeywords.SEEN),
                    condition("hasKeyword", CorvidKeywords.SEEN),
                    condition("hasKeyword", CorvidKeywords.FLAGGED),
                ),
                filter("is:unread is:read is:starred"),
            )
        }

    @Test
    fun `a date in either separator style is the start of that day where the user is`() {
        assertEquals(
            listOf(SearchTerm.Before(Instant.parse("2026-01-31T05:00:00Z"))),
            parse("before:2026-01-31", NEW_YORK).terms,
        )
        assertEquals(listOf(SearchTerm.After(Instant.parse("2026-01-31T00:00:00Z"))), parse("after:2026/1/31").terms)
    }

    @Test
    fun `older and newer than count back from now in whole seconds`() {
        assertEquals(
            listOf(
                SearchTerm.Before(Instant.parse("2026-10-03T12:00:30Z")),
                SearchTerm.After(Instant.parse("2026-08-10T12:00:30Z")),
                SearchTerm.Before(Instant.parse("2025-10-10T12:00:30Z")),
            ),
            parse("older_than:7d newer_than:2m older_than:1y").terms,
        )
    }

    @Test
    fun `dates are sent as RFC 8621 UTC dates`() =
        runTest {
            assertEquals(condition("after", "2026-10-09T12:00:30Z"), filter("newer_than:1d"))
        }

    @Test
    fun `an impossible date or age is searched as words`() {
        assertEquals(
            listOf(SearchTerm.Word("before:2026-02-30"), SearchTerm.Word("older_than:soon")),
            parse("before:2026-02-30 older_than:soon").terms,
        )
    }

    @Test
    fun `quoted words are searched as one phrase`() =
        runTest {
            assertEquals(condition("text", "\"exact words\""), filter("\"exact words\""))
        }

    @Test
    fun `a minus sign negates a word, a phrase or an operator`() =
        runTest {
            assertEquals(
                all(not(condition("text", "spam")), not(condition("text", "\"two words\"")), not(condition("from", "ada"))),
                filter("-spam -\"two words\" -from:ada"),
            )
        }

    @Test
    fun `unknown operators and values are searched as words`() {
        assertEquals(
            listOf(SearchTerm.Word("label:work"), SearchTerm.Word("is:important"), SearchTerm.Word("has:drive")),
            parse("label:work is:important has:drive").terms,
        )
    }

    @Test
    fun `an operator still being typed is left out rather than searched`() {
        val query = parse("invoice from:")

        assertEquals(listOf(SearchTerm.Word("invoice")), query.terms)
        assertNull(query.completing)
    }

    @Test
    fun `by default trash and junk are left out`() {
        assertEquals(SearchScope.OutsideDiscarded, parse("invoice").scope)
    }

    @Test
    fun `in anywhere searches every mailbox and adds no condition`() =
        runTest {
            assertEquals(SearchScope.Anywhere, parse("invoice in:anywhere").scope)
            assertEquals(condition("completing", "invoice"), filter("in:anywhere invoice"))
        }

    @Test
    fun `naming a mailbox searches only it, found by role`() =
        runTest {
            assertEquals(SearchScope.Named, parse("in:trash").scope)
            assertEquals(condition("inMailbox", "mb-trash"), filter("in:trash"))
            assertEquals(condition("inMailbox", "mb-junk"), filter("in:spam"))
        }

    @Test
    fun `a mailbox the account lacks matches nothing`() =
        runTest {
            assertNull(filter("in:archive invoice"))
        }

    @Test
    fun `a query of operators alone matches everything they allow`() =
        runTest {
            assertEquals(JsonObject(emptyMap()), filter("in:anywhere"))
        }

    @Test
    fun `in anywhere is added once and removed wherever it sits`() {
        assertEquals("invoice from:ada in:anywhere", searchAnywhere("invoice  from:ada", anywhere = true))
        assertEquals("invoice in:anywhere", searchAnywhere("invoice in:anywhere", anywhere = true))
        assertEquals("invoice \"two words\"", searchAnywhere("in:Anywhere invoice \"two words\"", anywhere = false))
    }
}
