package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.jmap.Invocation
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.resultOrThrow
import com.verdenroz.corvid.core.jmap.resultReference
import com.verdenroz.corvid.core.model.EmailAddress
import com.verdenroz.corvid.core.model.Rewrite
import com.verdenroz.corvid.core.model.Seal
import com.verdenroz.corvid.core.model.SenderStats
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

private const val STATS_PAGE = 256
private val FROM_PROPERTIES = listOf("id", "from", "receivedAt", AUTHENTICATION_RESULTS, CORVID_REWRITE)
private val SENT_PROPERTIES = listOf("id", "to", "cc", "bcc")

/**
 * A server's `from` and `to` filters match text, so "ada@x.test" also finds "lada@x.test" and
 * "ada@x.test.evil". Every candidate is fetched and kept only when an address matches exactly,
 * paging until the query is exhausted, so a count is never a guess.
 */
class SenderRepository(
    private val jmapClient: JmapClient,
    private val mailboxRepository: MailboxRepository,
) {
    suspend fun stats(address: String): SenderStats {
        val received = exactMatches(condition("from", address), FROM_PROPERTIES) { it.addressList("from").hasAddress(address) }
        val sentId = mailboxRepository.findMailboxByRole("sent")?.id
        val sent =
            sentId?.let { id ->
                val recipients =
                    buildJsonObject {
                        put("operator", "OR")
                        putJsonArray("conditions") { listOf("to", "cc", "bcc").forEach { add(condition(it, address)) } }
                    }
                val filter =
                    buildJsonObject {
                        put("operator", "AND")
                        putJsonArray("conditions") {
                            add(condition("inMailbox", id))
                            add(recipients)
                        }
                    }
                exactMatches(
                    filter,
                    SENT_PROPERTIES,
                ) { email -> listOf("to", "cc", "bcc").any { email.addressList(it).hasAddress(address) } }
            }.orEmpty()
        val rewrites = received.mapNotNull { readRewrite(it.texts(CORVID_REWRITE)) }
        return SenderStats(
            received = received.size,
            firstSeen = received.firstOrNull()?.let { Instant.parse(it.getValue("receivedAt").jsonPrimitive.content) },
            signed = received.count { readSeal(it.texts(AUTHENTICATION_RESULTS)) is Seal.Signed },
            marked = rewrites.size,
            trackersStripped = rewrites.sumOf { (it as? Rewrite.Stripped)?.stripped ?: 0 },
            sent = sent.size,
        )
    }

    /** Oldest first, so the first match is the first message ever seen. A mailbox that changes mid-count is counted again. */
    private suspend fun exactMatches(
        filter: JsonObject,
        properties: List<String>,
        keep: (JsonObject) -> Boolean,
    ): List<JsonObject> {
        val kept = mutableListOf<JsonObject>()
        var position = 0
        var queryState: String? = null
        do {
            val page = page(filter, properties, position)
            if (queryState != null && page.queryState != queryState) {
                kept.clear()
                position = 0
                queryState = null
                continue
            }
            queryState = page.queryState
            kept += page.emails.filter(keep)
            position += STATS_PAGE
        } while (position < page.total)
        return kept
    }

    private suspend fun page(
        filter: JsonObject,
        properties: List<String>,
        position: Int,
    ): StatsPage {
        val query =
            buildJsonObject {
                put("filter", filter)
                putJsonArray("sort") {
                    addJsonObject {
                        put("property", "receivedAt")
                        put("isAscending", true)
                    }
                }
                put("position", position)
                put("limit", STATS_PAGE)
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
