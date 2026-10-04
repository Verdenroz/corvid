package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.MethodOutcome
import com.verdenroz.corvid.core.jmap.callOne
import com.verdenroz.corvid.core.jmap.resultOrThrow
import com.verdenroz.corvid.core.jmap.resultReference
import com.verdenroz.corvid.core.jmap.setErrorReason
import com.verdenroz.corvid.core.model.CorvidKeywords
import com.verdenroz.corvid.core.model.EmailAddress
import com.verdenroz.corvid.core.model.EmailDetail
import com.verdenroz.corvid.core.model.EmailSummary
import com.verdenroz.corvid.core.model.Identity
import com.verdenroz.corvid.core.model.Mailbox
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
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
internal val EMAIL_PROPERTIES = listOf("id", "threadId", "subject", "from", "receivedAt", "keywords", "preview") + PROVENANCE_PROPERTIES
private val EMAIL_DETAIL_PROPERTIES =
    listOf("id", "subject", "from", "to", "cc", "replyTo", "messageId", "references", "receivedAt", "keywords", "textBody", "bodyValues") +
        PROVENANCE_PROPERTIES

/** What a list shows: one mailbox, or every starred message wherever it is filed. */
sealed interface MailFilter {
    data class InMailbox(val mailboxId: String) : MailFilter

    data object Starred : MailFilter
}

sealed interface EmailPage {
    data class Found(val emails: List<EmailSummary>, val queryState: String) : EmailPage

    data object AnchorNotFound : EmailPage
}

/** Keywords set (true) or cleared (false) on one message, as the server accepted them. */
data class KeywordPatch(val emailId: String, val patch: Map<String, Boolean>) {
    fun applyTo(keywords: Set<String>): Set<String> = keywords + patch.filterValues { it }.keys - patch.filterValues { !it }.keys
}

sealed interface EmailLookup {
    data class Found(val detail: EmailDetail) : EmailLookup

    /** The message was deleted or moved between the inbox list and opening it. */
    data object NotFound : EmailLookup
}

/**
 * Query and get run as one JMAP request, the get's `ids`
 * resolved server-side from the query's result via a [resultReference].
 * Whether an anchor lost meaning (queryState changed) is for the caller to
 * decide by comparing to the queryState it already holds; this repository
 * is stateless across pages.
 */
class MailboxRepository(private val jmapClient: JmapClient) {
    private val identityLock = Mutex()
    private val rolesLock = Mutex()
    private var identity: KnownIdentity? = null
    private var roleIds: Map<String, String>? = null
    private val _keywordPatches = MutableSharedFlow<KeywordPatch>(extraBufferCapacity = 16)

    /** Every patch [updateKeywords] got through, so a list already on screen can apply it without refetching. */
    val keywordPatches: SharedFlow<KeywordPatch> = _keywordPatches

    suspend fun mailboxes(): List<Mailbox> =
        jmapClient.callOne("Mailbox/get", JsonObject(emptyMap())).getValue("list").jsonArray.map { it.jsonObject.toMailbox() }

    /** Counts change with every message, so this always asks the server; for the id alone use [mailboxId]. */
    suspend fun findMailboxByRole(role: String): Mailbox? = mailboxes().firstOrNull { it.role == role }

    /** The id of the mailbox with [role], fetched once: roles do not move while the app is open. */
    suspend fun mailboxId(role: String): String? =
        rolesLock.withLock {
            val ids = roleIds ?: mailboxes().mapNotNull { box -> box.role?.let { it to box.id } }.toMap().also { roleIds = it }
            ids[role]
        }

    suspend fun page(
        filter: MailFilter,
        anchor: String?,
    ): EmailPage {
        val queryArguments =
            buildJsonObject {
                putJsonObject("filter") {
                    when (filter) {
                        is MailFilter.InMailbox -> put("inMailbox", filter.mailboxId)
                        is MailFilter.Starred -> put("hasKeyword", CorvidKeywords.FLAGGED)
                    }
                }
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
        val outcomes = jmapClient.queryAndGet(queryArguments, EMAIL_PROPERTIES)
        if ((outcomes.query as? MethodOutcome.Failure)?.error?.type == "anchorNotFound") return EmailPage.AnchorNotFound
        val queryResult = outcomes.query.resultOrThrow()
        val getResult = outcomes.get.resultOrThrow()
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

    /** One message's keywords alone; null when it is gone. */
    suspend fun keywords(id: String): Set<String>? {
        val arguments =
            buildJsonObject {
                putJsonArray("ids") { add(JsonPrimitive(id)) }
                putJsonArray("properties") { add(JsonPrimitive("keywords")) }
            }
        val found = jmapClient.callOne("Email/get", arguments).getValue("list").jsonArray.firstOrNull() ?: return null
        return found.jsonObject.getValue("keywords").jsonObject.keys
    }

    /**
     * Patches only the named keywords, so anything else on the message, including the labeler's,
     * is left as it is. Returns the server's reason when it refuses, null when it took the patch.
     */
    suspend fun updateKeywords(
        id: String,
        patch: Map<String, Boolean>,
    ): String? {
        val arguments =
            buildJsonObject {
                putJsonObject("update") {
                    putJsonObject(id) {
                        patch.forEach {
                                (keyword, set) ->
                            put("keywords/${keyword.pointerEscaped()}", if (set) JsonPrimitive(true) else JsonNull)
                        }
                    }
                }
            }
        val refused = jmapClient.callOne("Email/set", arguments).setErrorReason("notUpdated", id)
        if (refused == null) _keywordPatches.emit(KeywordPatch(id, patch))
        return refused
    }

    /** The account's first identity, fetched once: who the account avatar is, and which senders are its own server. */
    suspend fun identity(): Identity? =
        identityLock.withLock {
            (identity ?: KnownIdentity(fetchIdentity()).also { identity = it }).identity
        }

    internal suspend fun ownDomain(): String? = identity()?.email?.substringAfterLast('@')

    private suspend fun fetchIdentity(): Identity? =
        jmapClient.callOne("Identity/get", JsonObject(emptyMap())).getValue("list").jsonArray.firstOrNull()?.jsonObject?.toIdentity()
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
internal fun JsonObject.toEmailSummary(ownDomain: String?): EmailSummary {
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

private class KnownIdentity(val identity: Identity?)

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

/** RFC 6901: a keyword holding `~` or `/` must not split the patch path. */
private fun String.pointerEscaped(): String = replace("~", "~0").replace("/", "~1")
