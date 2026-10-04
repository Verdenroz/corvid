package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.jmap.Invocation
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.resultOrThrow
import com.verdenroz.corvid.core.jmap.resultReference
import com.verdenroz.corvid.core.model.EmailAddress
import com.verdenroz.corvid.core.model.Rewrite
import com.verdenroz.corvid.core.model.Seal
import com.verdenroz.corvid.core.model.SenderCounts
import com.verdenroz.corvid.core.model.SenderWindow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.time.Instant

private const val PAGE = 500
const val SENDER_WINDOW = 200
private val COUNT_PROPERTIES = listOf("id", "from", "receivedAt")
private val SENT_PROPERTIES = listOf("id", "to", "cc", "bcc")
private val WINDOW_PROPERTIES = listOf("id", "from", AUTHENTICATION_RESULTS, CORVID_REWRITE)

/**
 * A server's `from` and `to` filters match text, so "ada@x.test" also finds "lada@x.test" and
 * "ada@x.test.evil". Every candidate is checked and kept only when an address matches exactly.
 * Counting reads three small properties per message; header facts read only the newest
 * [SENDER_WINDOW] matches. Both stop at the next request once their coroutine is cancelled.
 */
class SenderRepository(
    private val jmapClient: JmapClient,
    private val mailboxRepository: MailboxRepository,
) {
    suspend fun counts(address: String): SenderCounts {
        val received =
            exactMatches(condition("from", address), COUNT_PROPERTIES, ascending = true) { it.addressList("from").hasAddress(address) }
        val sent = mailboxRepository.findMailboxByRole("sent")?.id?.let { sentMatches(it, address) }.orEmpty()
        return SenderCounts(
            received = received.size,
            firstSeen = received.firstOrNull()?.let { Instant.parse(it.getValue("receivedAt").jsonPrimitive.content) },
            sent = sent.size,
        )
    }

    suspend fun window(address: String): SenderWindow {
        val newest =
            exactMatches(condition("from", address), WINDOW_PROPERTIES, ascending = false, enough = SENDER_WINDOW) {
                it.addressList("from").hasAddress(address)
            }.take(SENDER_WINDOW)
        val rewrites = newest.mapNotNull { readRewrite(it.texts(CORVID_REWRITE)) }
        return SenderWindow(
            size = newest.size,
            signed = newest.count { readSeal(it.texts(AUTHENTICATION_RESULTS)) is Seal.Signed },
            marked = rewrites.size,
            trackersStripped = rewrites.sumOf { (it as? Rewrite.Stripped)?.stripped ?: 0 },
        )
    }

    private suspend fun sentMatches(
        sentId: String,
        address: String,
    ): List<JsonObject> {
        val recipients =
            buildJsonObject {
                put("operator", "OR")
                putJsonArray("conditions") { listOf("to", "cc", "bcc").forEach { add(condition(it, address)) } }
            }
        val filter =
            buildJsonObject {
                put("operator", "AND")
                putJsonArray("conditions") {
                    add(condition("inMailbox", sentId))
                    add(recipients)
                }
            }
        return exactMatches(filter, SENT_PROPERTIES, ascending = true) { email ->
            listOf("to", "cc", "bcc").any {
                email.addressList(it).hasAddress(address)
            }
        }
    }

    /** Pages through every candidate until [enough] exact matches are kept; a mailbox that changes mid-count is counted again. */
    private suspend fun exactMatches(
        filter: JsonObject,
        properties: List<String>,
        ascending: Boolean,
        enough: Int = Int.MAX_VALUE,
        keep: (JsonObject) -> Boolean,
    ): List<JsonObject> {
        val kept = mutableListOf<JsonObject>()
        var position = 0
        var queryState: String? = null
        do {
            val page = page(filter, properties, ascending, position)
            if (queryState != null && page.queryState != queryState) {
                kept.clear()
                position = 0
                queryState = null
                continue
            }
            queryState = page.queryState
            kept += page.emails.filter(keep)
            position += PAGE
        } while (position < page.total && kept.size < enough)
        return kept
    }

    private suspend fun page(
        filter: JsonObject,
        properties: List<String>,
        ascending: Boolean,
        position: Int,
    ): StatsPage {
        val query =
            buildJsonObject {
                put("filter", filter)
                putJsonArray("sort") {
                    addJsonObject {
                        put("property", "receivedAt")
                        put("isAscending", ascending)
                    }
                }
                put("position", position)
                put("limit", PAGE)
                put("calculateTotal", true)
            }
        val get =
            buildJsonObject {
                put("#ids", resultReference(resultOf = "c0", name = "Email/query", path = "/ids"))
                put("properties", JsonArray(properties.map(::JsonPrimitive)))
            }
        val outcomes = jmapClient.call(listOf(Invocation("Email/query", query, "c0"), Invocation("Email/get", get, "c1")))
        val found = outcomes.getValue("c0").resultOrThrow()
        return StatsPage(
            total = found.getValue("total").jsonPrimitive.int,
            queryState = found.getValue("queryState").jsonPrimitive.content,
            emails = outcomes.getValue("c1").resultOrThrow().getValue("list").jsonArray.map { it.jsonObject },
        )
    }
}

private class StatsPage(
    val total: Int,
    val queryState: String,
    val emails: List<JsonObject>,
)

private fun condition(
    key: String,
    value: String,
): JsonObject = buildJsonObject { put(key, value) }

private fun List<EmailAddress>.hasAddress(address: String): Boolean = any { it.email.equals(address, ignoreCase = true) }
