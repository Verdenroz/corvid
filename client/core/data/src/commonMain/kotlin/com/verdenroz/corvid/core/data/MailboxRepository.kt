package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.jmap.Invocation
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.jmap.MethodOutcome
import com.verdenroz.corvid.core.jmap.resultReference
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
private val EMAIL_PROPERTIES = listOf("id", "threadId", "subject", "from", "receivedAt", "keywords", "preview")
private val EMAIL_DETAIL_PROPERTIES = listOf("id", "subject", "from", "to", "cc", "receivedAt", "textBody", "bodyValues")

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
    suspend fun findMailboxByRole(role: String): Mailbox? {
        val result = call("Mailbox/get", JsonObject(emptyMap()))
        return result.getValue("list").jsonArray
            .map { it.jsonObject.toMailbox() }
            .firstOrNull { it.role == role }
    }

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
        val emails = getResult.getValue("list").jsonArray.map { it.jsonObject.toEmailSummary() }
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
        val result = call("Email/get", arguments)
        if (result.getValue("notFound").jsonArray.isNotEmpty()) return EmailLookup.NotFound
        return EmailLookup.Found(result.getValue("list").jsonArray.first().jsonObject.toEmailDetail())
    }

    private suspend fun call(
        method: String,
        arguments: JsonObject,
    ): JsonObject = jmapClient.call(listOf(Invocation(method, arguments, "c0"))).getValue("c0").resultOrThrow()
}

private fun MethodOutcome.resultOrThrow(): JsonObject =
    when (this) {
        is MethodOutcome.Success -> result
        is MethodOutcome.Failure -> throw JmapException(error)
    }

private fun JsonObject.toMailbox() =
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
private fun JsonObject.toEmailSummary() =
    EmailSummary(
        id = getValue("id").jsonPrimitive.content,
        threadId = getValue("threadId").jsonPrimitive.content,
        subject = get("subject")?.jsonPrimitive?.contentOrNull.orEmpty(),
        from = fromAddress(),
        receivedAt = Instant.parse(getValue("receivedAt").jsonPrimitive.content),
        keywords = getValue("keywords").jsonObject.keys,
        preview = getValue("preview").jsonPrimitive.content,
    )

private fun JsonObject.fromAddress(): String =
    (get("from") as? JsonArray)?.mapNotNull { it.jsonObject.formatAddress() }?.firstOrNull().orEmpty()

/** email is null for MIME group syntax ("undisclosed-recipients:;"), which carries a name and no address. */
private fun JsonObject.formatAddress(): String? {
    val email = get("email")?.jsonPrimitive?.contentOrNull ?: return null
    val name = get("name")?.jsonPrimitive?.contentOrNull
    return if (name != null) "$name <$email>" else email
}

private fun JsonObject.addressList(key: String): List<String> =
    (get(key) as? JsonArray)?.mapNotNull { it.jsonObject.formatAddress() }.orEmpty()

/**
 * textBody already picks the best single-part representation per RFC 8621; an
 * HTML-only message arrives here as one text/html part, flattened rather than
 * shown, since this client has no HTML renderer.
 */
private fun JsonObject.toEmailDetail(): EmailDetail {
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
    return EmailDetail(
        id = getValue("id").jsonPrimitive.content,
        subject = get("subject")?.jsonPrimitive?.contentOrNull.orEmpty(),
        from = fromAddress(),
        to = addressList("to"),
        cc = addressList("cc"),
        receivedAt = Instant.parse(getValue("receivedAt").jsonPrimitive.content),
        body = parts.joinToString("\n\n") { it.first },
        bodyTruncated = parts.any { it.second },
    )
}
