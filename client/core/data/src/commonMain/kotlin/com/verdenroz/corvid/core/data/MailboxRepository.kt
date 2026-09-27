package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.jmap.Invocation
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.jmap.MethodOutcome
import com.verdenroz.corvid.core.jmap.resultReference
import com.verdenroz.corvid.core.model.EmailSummary
import com.verdenroz.corvid.core.model.Mailbox
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
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
private val EMAIL_PROPERTIES = listOf("id", "threadId", "subject", "from", "receivedAt", "keywords", "preview")

sealed interface EmailPage {
    data class Found(val emails: List<EmailSummary>, val queryState: String) : EmailPage

    data object AnchorNotFound : EmailPage
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

// subject and from are String|null and EmailAddress[]|null in RFC 8621: jsonPrimitive.content
// on a JsonNull subject returns the literal string "null", and .jsonArray on a JsonNull
// from throws, so both need a null check before the normal parse.
private fun JsonObject.toEmailSummary() =
    EmailSummary(
        id = getValue("id").jsonPrimitive.content,
        threadId = getValue("threadId").jsonPrimitive.content,
        subject = get("subject")?.jsonPrimitive?.contentOrNull.orEmpty(),
        from = (get("from") as? JsonArray)?.firstOrNull()?.jsonObject?.formatAddress().orEmpty(),
        receivedAt = Instant.parse(getValue("receivedAt").jsonPrimitive.content),
        keywords = getValue("keywords").jsonObject.keys,
        preview = getValue("preview").jsonPrimitive.content,
    )

private fun JsonObject.formatAddress(): String {
    val email = getValue("email").jsonPrimitive.content
    val name = get("name")?.jsonPrimitive?.contentOrNull
    return if (name != null) "$name <$email>" else email
}
