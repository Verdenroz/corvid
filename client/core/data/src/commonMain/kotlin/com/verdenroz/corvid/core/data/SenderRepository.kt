package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.resultOrThrow
import com.verdenroz.corvid.core.model.EmailAddress
import com.verdenroz.corvid.core.model.EmailSummary
import com.verdenroz.corvid.core.model.Rewrite
import com.verdenroz.corvid.core.model.Seal
import com.verdenroz.corvid.core.model.SenderCounts
import com.verdenroz.corvid.core.model.SenderWindow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Instant

private const val PAGE = 500
const val SENDER_WINDOW = 200
private const val MAX_RESTARTS = 3
private val COUNT_PROPERTIES = listOf("id", "from", "receivedAt")
private val RECIPIENT_FIELDS = listOf("to", "cc", "bcc")
private val SENT_PROPERTIES = listOf("id") + RECIPIENT_FIELDS

/**
 * A server's `from` and `to` filters match text, so "ada@x.test" also finds "lada@x.test" and
 * "ada@x.test.evil". Every candidate is checked and kept only when an address matches exactly.
 * Counting reads three small properties per message; [newest] reads whole summaries, so only
 * [SENDER_WINDOW] of them. Both stop at the next request once their coroutine is cancelled, and
 * return null rather than a number when the mailbox keeps changing under them.
 */
class SenderRepository(
    private val jmapClient: JmapClient,
    private val mailboxRepository: MailboxRepository,
) {
    suspend fun counts(address: String): SenderCounts? {
        val fromThem = condition("from", address)
        val received =
            exactMatches(fromThem, COUNT_PROPERTIES, ascending = true, PAGE) {
                it.addressList("from").hasAddress(address)
            } ?: return null
        val sentId = mailboxRepository.mailboxId(SENT)
        val sent = if (sentId == null) emptyList() else sentMatches(sentId, address) ?: return null
        return SenderCounts(
            received = received.size,
            firstSeen = received.firstOrNull()?.let { Instant.parse(it.getValue("receivedAt").jsonPrimitive.content) },
            sent = sent.size,
        )
    }

    /** The newest [SENDER_WINDOW] messages from exactly [address], newest first. */
    suspend fun newest(address: String): List<EmailSummary>? {
        val matches =
            exactMatches(condition("from", address), EMAIL_PROPERTIES, ascending = false, SENDER_WINDOW, enough = SENDER_WINDOW) {
                it.addressList("from").hasAddress(address)
            } ?: return null
        val domain = mailboxRepository.ownDomain()
        return matches.take(SENDER_WINDOW).map { it.toEmailSummary(domain) }
    }

    private suspend fun sentMatches(
        sentId: String,
        address: String,
    ): List<JsonObject>? {
        val recipients = operator("OR", RECIPIENT_FIELDS.map { condition(it, address) })
        val filter = operator("AND", listOf(condition("inMailbox", sentId), recipients))
        return exactMatches(filter, SENT_PROPERTIES, ascending = true, PAGE) { email ->
            RECIPIENT_FIELDS.any { email.addressList(it).hasAddress(address) }
        }
    }

    /**
     * Pages through every candidate until [enough] exact matches are kept. A mailbox that changes
     * mid-count is counted again from the start, at most [MAX_RESTARTS] times; after that, null.
     */
    private suspend fun exactMatches(
        filter: JsonObject,
        properties: List<String>,
        ascending: Boolean,
        pageSize: Int,
        enough: Int = Int.MAX_VALUE,
        keep: (JsonObject) -> Boolean,
    ): List<JsonObject>? {
        val kept = mutableListOf<JsonObject>()
        var position = 0
        var queryState: String? = null
        var restarts = 0
        do {
            val page = page(filter, properties, ascending, position, pageSize)
            if (queryState != null && page.queryState != queryState) {
                if (++restarts > MAX_RESTARTS) return null
                kept.clear()
                position = 0
                queryState = null
                continue
            }
            queryState = page.queryState
            kept += page.emails.filter(keep)
            position += pageSize
        } while (position < page.total && kept.size < enough)
        return kept
    }

    private suspend fun page(
        filter: JsonObject,
        properties: List<String>,
        ascending: Boolean,
        position: Int,
        pageSize: Int,
    ): StatsPage {
        val query =
            buildJsonObject {
                put("filter", filter)
                put("sort", receivedAtSort(ascending))
                put("position", position)
                put("limit", pageSize)
                put("calculateTotal", true)
            }
        val outcomes = jmapClient.queryAndGet(query, properties)
        val found = outcomes.query.resultOrThrow()
        return StatsPage(
            total = found.getValue("total").jsonPrimitive.int,
            queryState = found.getValue("queryState").jsonPrimitive.content,
            emails = outcomes.get.resultOrThrow().getValue("list").jsonArray.map { it.jsonObject },
        )
    }
}

private class StatsPage(
    val total: Int,
    val queryState: String,
    val emails: List<JsonObject>,
)

private fun List<EmailAddress>.hasAddress(address: String): Boolean = any { it.email.equals(address, ignoreCase = true) }

/** What a sender's newest messages say about them, from the provenance each one already carries. */
fun senderWindow(newest: List<EmailSummary>): SenderWindow {
    val rewrites = newest.mapNotNull { it.provenance.rewrite }
    return SenderWindow(
        size = newest.size,
        signed = newest.count { it.provenance.seal is Seal.Signed },
        marked = rewrites.size,
        trackersStripped = rewrites.sumOf { (it as? Rewrite.Stripped)?.stripped ?: 0 },
    )
}
