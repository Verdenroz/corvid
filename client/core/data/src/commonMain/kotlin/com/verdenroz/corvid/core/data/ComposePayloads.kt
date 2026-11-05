package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.jmap.Invocation
import com.verdenroz.corvid.core.jmap.resultReference
import com.verdenroz.corvid.core.model.CorvidKeywords
import com.verdenroz.corvid.core.model.Draft
import com.verdenroz.corvid.core.model.EmailAddress
import com.verdenroz.corvid.core.model.Identity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private const val BODY_PART_ID = "body"

/** [messageId] and [keyword], both set or both null, name the draft so a retry can find it again. */
internal fun createEmail(
    identity: Identity,
    draft: Draft,
    draftsId: String,
    messageId: String? = null,
    keyword: String? = null,
): JsonObject =
    buildJsonObject {
        putJsonObject("create") {
            putJsonObject("draft") {
                putJsonObject("mailboxIds") { put(draftsId, true) }
                putJsonObject("keywords") {
                    put(CorvidKeywords.DRAFT, true)
                    put(CorvidKeywords.SEEN, true)
                    keyword?.let { put(it, true) }
                }
                messageId?.let { putJsonArray("messageId") { add(JsonPrimitive(it)) } }
                putAddresses("from", listOf(EmailAddress(identity.name.ifEmpty { null }, identity.email)))
                putAddresses("to", draft.to)
                putAddresses("cc", draft.cc)
                putAddresses("bcc", draft.bcc)
                put("subject", draft.subject)
                if (draft.inReplyTo.isNotEmpty()) put("inReplyTo", JsonArray(draft.inReplyTo.map(::JsonPrimitive)))
                if (draft.references.isNotEmpty()) put("references", JsonArray(draft.references.map(::JsonPrimitive)))
                putJsonArray("textBody") {
                    addJsonObject {
                        put("partId", BODY_PART_ID)
                        put("type", "text/plain")
                    }
                }
                putJsonObject("bodyValues") { putJsonObject(BODY_PART_ID) { put("value", draft.body) } }
                if (draft.attachments.isNotEmpty()) {
                    putJsonArray("attachments") {
                        draft.attachments.forEach { attachment ->
                            addJsonObject {
                                put("blobId", attachment.blobId)
                                put("type", attachment.type)
                                put("name", attachment.fileName())
                                put("disposition", "attachment")
                            }
                        }
                    }
                }
            }
        }
    }

internal fun submit(
    identity: Identity,
    emailId: String,
    draftsId: String,
    sentId: String,
): JsonObject =
    buildJsonObject {
        putJsonObject("create") {
            putJsonObject("submission") {
                put("identityId", identity.id)
                put("emailId", emailId)
            }
        }
        putJsonObject("onSuccessUpdateEmail") {
            putJsonObject("#submission") {
                put("mailboxIds/$draftsId", JsonNull)
                put("mailboxIds/$sentId", true)
                put("keywords/${CorvidKeywords.DRAFT}", JsonNull)
                put("keywords/${CorvidKeywords.SEEN}", true)
            }
        }
    }

/** Finds the message marked [keyword] in any mailbox and reads where it is and what it is flagged as. */
internal fun findByKeyword(keyword: String): List<Invocation> =
    listOf(
        Invocation(
            "Email/query",
            buildJsonObject { putJsonObject("filter") { put("hasKeyword", keyword) } },
            "find",
        ),
        Invocation(
            "Email/get",
            buildJsonObject {
                put("#ids", resultReference("find", "Email/query", "/ids"))
                putJsonArray("properties") {
                    add(JsonPrimitive("mailboxIds"))
                    add(JsonPrimitive("keywords"))
                }
            },
            "read",
        ),
    )

internal fun destroyEmail(emailId: String): JsonObject = buildJsonObject { putJsonArray("destroy") { add(JsonPrimitive(emailId)) } }

/** Drops the send mark from the sent copy and destroys the draft this message replaced, whichever are still set. */
internal fun tidySent(
    emailId: String,
    keyword: String?,
    replacing: String?,
): JsonObject =
    buildJsonObject {
        keyword?.let { putJsonObject("update") { putJsonObject(emailId) { put("keywords/$it", JsonNull) } } }
        replacing?.let { putJsonArray("destroy") { add(JsonPrimitive(it)) } }
    }

internal val DRAFT_PROPERTIES =
    listOf("from", "to", "cc", "bcc", "subject", "inReplyTo", "references", "keywords", "textBody", "bodyValues", "attachments")

/** A draft from [mainEmail] reads back with no alias, as one made here would. */
internal fun JsonObject.toDraft(mainEmail: String): Draft {
    val values = getValue("bodyValues").jsonObject
    val body =
        getValue("textBody").jsonArray.joinToString("\n\n") {
            values.getValue(it.jsonObject.getValue("partId").jsonPrimitive.content).jsonObject.getValue("value").jsonPrimitive.content
        }
    return Draft(
        to = addressList("to"),
        cc = addressList("cc"),
        bcc = addressList("bcc"),
        subject = get("subject")?.jsonPrimitive?.contentOrNull.orEmpty(),
        body = body,
        inReplyTo = idList("inReplyTo"),
        references = idList("references"),
        attachments = attachmentList(html = null),
        from = addressList("from").firstOrNull()?.email?.takeUnless { it.equals(mainEmail, ignoreCase = true) },
    )
}

private fun JsonObjectBuilder.putAddresses(
    key: String,
    addresses: List<EmailAddress>,
) {
    if (addresses.isEmpty()) return
    putJsonArray(key) {
        addresses.forEach { address ->
            addJsonObject {
                address.name?.let { put("name", it) }
                put("email", address.email)
            }
        }
    }
}
