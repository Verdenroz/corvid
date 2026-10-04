package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.jmap.Invocation
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.MethodOutcome
import com.verdenroz.corvid.core.jmap.callOne
import com.verdenroz.corvid.core.jmap.resultOrThrow
import com.verdenroz.corvid.core.jmap.resultReference
import com.verdenroz.corvid.core.model.EmailAddress
import com.verdenroz.corvid.core.model.EmailDetail
import com.verdenroz.corvid.core.model.EmailSummary
import com.verdenroz.corvid.core.model.Mailbox
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Instant

private const val PAGE_SIZE = 50
private const val MAX_BODY_VALUE_BYTES = 262_144
private val EMAIL_PROPERTIES = listOf("id", "threadId", "subject", "from", "receivedAt", "keywords", "preview") + PROVENANCE_PROPERTIES
private val EMAIL_DETAIL_PROPERTIES =
    listOf("id", "subject", "from", "to", "cc", "replyTo", "messageId", "references", "receivedAt", "keywords", "textBody", "bodyValues") +
        PROVENANCE_PROPERTIES

sealed interface EmailPage {
    data class Found(val emails: List<EmailSummary>, val queryState: String) : EmailPage

    data object AnchorNotFound : EmailPage
}

sealed interface EmailLookup {
    data class Found(val detail: EmailDetail) : EmailLookup

    /** The message was deleted or moved between the inbox list and opening it. */
    data object NotFound : EmailLookup
}

/**
 * Query and get run as one JMAP request (decision 8), the get's `ids`
 * resolved server-side from the query's result via a [resultReference].
 * Whether an anchor lost meaning (queryState changed) is for the caller to
 * decide by comparing to the queryState it already holds; this repository
 * is stateless across pages.
 */
class MailboxRepository(private val jmapClient: JmapClient) {
    private var ownDomain: OwnDomain? = null

    suspend fun mailboxes(): List<Mailbox> =
        jmapClient.callOne("Mailbox/get", JsonObject(emptyMap())).getValue("list").jsonArray.map { it.jsonObject.toMailbox() }

    suspend fun findMailboxByRole(role: String): Mailbox? = mailboxes().firstOrNull { it.role == role }

    suspend fun page(
        mailboxId: String,
        anchor: String?,
    ): EmailPage {
        val queryArguments =
            buildJsonObject {
                putJsonObject("filter") { put("inMailbox", mailboxId) }
                putJsonArray("sort") {
                    addJsonObject {
                        put("property", "receivedAt")
                        put("isAscending", false)
                    }
                }
                put("collapseThreads", false)
                put("limit", PAGE_SIZE)
                anchor?.let {
                    put("anchor", it)
                    put("anchorOffset", 1)
                }
            }
        val getArguments =
            buildJsonObject {
                put("#ids", resultReference(resultOf = "c0", name = "Email/query", path = "/ids"))
                putJsonArray("properties") { EMAIL_PROPERTIES.forEach { add(JsonPrimitive(it)) } }
            }
        val outcomes =
            jmapClient.call(
                listOf(
                    Invocation("Email/query", queryArguments, "c0"),
                    Invocation("Email/get", getArguments, "c1"),
                ),
            )
        val queryOutcome = outcomes.getValue("c0")
        if ((queryOutcome as? MethodOutcome.Failure)?.error?.type == "anchorNotFound") return EmailPage.AnchorNotFound
        val queryResult = queryOutcome.resultOrThrow()
        val getResult = outcomes.getValue("c1").resultOrThrow()
        val domain = ownDomain()
        val emails = getResult.getValue("list").jsonArray.map { it.jsonObject.toEmailSummary(domain) }
        return EmailPage.Found(emails, queryResult.getValue("queryState").jsonPrimitive.content)
    }

    suspend fun getEmail(id: String): EmailLookup {
        val arguments =
            buildJsonObject {
                putJsonArray("ids") { add(JsonPrimitive(id)) }
                putJsonArray("properties") { EMAIL_DETAIL_PROPERTIES.forEach { add(JsonPrimitive(it)) } }
                put("fetchTextBodyValues", true)
                put("maxBodyValueBytes", MAX_BODY_VALUE_BYTES)
            }
        val result = jmapClient.callOne("Email/get", arguments)
        if (result.getValue("notFound").jsonArray.isNotEmpty()) return EmailLookup.NotFound
        return EmailLookup.Found(result.getValue("list").jsonArray.first().jsonObject.toEmailDetail(ownDomain()))
    }

    /** The account's first identity's domain, fetched once; it only names which kind of source a sender is. */
    private suspend fun ownDomain(): String? {
        val known = ownDomain ?: OwnDomain(fetchOwnDomain()).also { ownDomain = it }
        return known.domain
    }

    private suspend fun fetchOwnDomain(): String? =
        jmapClient.callOne("Identity/get", JsonObject(emptyMap())).getValue("list").jsonArray.firstOrNull()
            ?.jsonObject?.get("email")?.jsonPrimitive?.content?.substringAfterLast('@')
}

internal fun JsonObject.toMailbox() =
    Mailbox(
        id = getValue("id").jsonPrimitive.content,
        name = getValue("name").jsonPrimitive.content,
        role = get("role")?.jsonPrimitive?.contentOrNull,
        parentId = get("parentId")?.jsonPrimitive?.contentOrNull,
        totalEmails = get("totalEmails")?.jsonPrimitive?.int ?: 0,
        unreadEmails = get("unreadEmails")?.jsonPrimitive?.int ?: 0,
    )

// subject and from are nullable in RFC 8621: reading a null subject as a plain string
// returns the literal word "null", and reading a null from as an array throws.
private fun JsonObject.toEmailSummary(ownDomain: String?): EmailSummary {
    val from = addressList("from").firstOrNull()
    return EmailSummary(
        id = getValue("id").jsonPrimitive.content,
        threadId = getValue("threadId").jsonPrimitive.content,
        subject = get("subject")?.jsonPrimitive?.contentOrNull.orEmpty(),
        from = from,
        receivedAt = Instant.parse(getValue("receivedAt").jsonPrimitive.content),
        keywords = getValue("keywords").jsonObject.keys,
        preview = getValue("preview").jsonPrimitive.content,
        provenance = provenance(from, ownDomain),
    )
}

private class OwnDomain(val domain: String?)

/** email is null for MIME group syntax ("undisclosed-recipients:;"), which carries a name and no address. */
private fun JsonObject.toEmailAddress(): EmailAddress? {
    val email = get("email")?.jsonPrimitive?.contentOrNull ?: return null
    return EmailAddress(name = get("name")?.jsonPrimitive?.contentOrNull, email = email)
}

internal fun JsonObject.addressList(key: String): List<EmailAddress> =
    (get(key) as? JsonArray)?.mapNotNull { it.jsonObject.toEmailAddress() }.orEmpty()

private fun JsonObject.idList(key: String): List<String> = (get(key) as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()

/**
 * textBody already picks the best single-part representation per RFC 8621; an
 * HTML-only message arrives here as one text/html part, flattened rather than
 * shown, since this client has no HTML renderer.
 */
private fun JsonObject.toEmailDetail(ownDomain: String?): EmailDetail {
    val bodyValues = getValue("bodyValues").jsonObject
    val parts =
        getValue("textBody").jsonArray.map { part ->
            val partObject = part.jsonObject
            val partId = partObject.getValue("partId").jsonPrimitive.content
            val type = partObject.getValue("type").jsonPrimitive.content
            val value = bodyValues.getValue(partId).jsonObject
            val raw = value.getValue("value").jsonPrimitive.content
            val text = if (type == "text/html") flattenHtml(raw) else raw
            text to value.getValue("isTruncated").jsonPrimitive.boolean
        }
    val from = addressList("from").firstOrNull()
    return EmailDetail(
        id = getValue("id").jsonPrimitive.content,
        subject = get("subject")?.jsonPrimitive?.contentOrNull.orEmpty(),
        from = from,
        to = addressList("to"),
        cc = addressList("cc"),
        replyTo = addressList("replyTo"),
        messageId = idList("messageId"),
        references = idList("references"),
        receivedAt = Instant.parse(getValue("receivedAt").jsonPrimitive.content),
        body = parts.joinToString("\n\n") { it.first },
        bodyTruncated = parts.any { it.second },
        keywords = getValue("keywords").jsonObject.keys,
        provenance = provenance(from, ownDomain),
    )
}
