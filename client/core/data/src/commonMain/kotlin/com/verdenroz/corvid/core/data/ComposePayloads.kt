package com.verdenroz.corvid.core.data

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

internal fun createEmail(
    identity: Identity,
    draft: Draft,
    draftsId: String,
    replacing: String?,
): JsonObject =
    buildJsonObject {
        replacing?.let { putJsonArray("destroy") { add(JsonPrimitive(it)) } }
        putJsonObject("create") {
            putJsonObject("draft") {
                putJsonObject("mailboxIds") { put(draftsId, true) }
                putJsonObject("keywords") {
                    put(CorvidKeywords.DRAFT, true)
                    put(CorvidKeywords.SEEN, true)
                }
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

internal val DRAFT_PROPERTIES =
    listOf("to", "cc", "bcc", "subject", "inReplyTo", "references", "keywords", "textBody", "bodyValues")

internal fun JsonObject.toDraft(): Draft {
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
