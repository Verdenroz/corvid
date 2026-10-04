package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.jmap.Invocation
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.JmapError
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.jmap.MethodOutcome
import com.verdenroz.corvid.core.jmap.callOne
import com.verdenroz.corvid.core.jmap.reason
import com.verdenroz.corvid.core.jmap.setErrorReason
import com.verdenroz.corvid.core.model.Draft
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

sealed interface SendResult {
    data object Sent : SendResult

    /** The server refused the draft or its submission; nothing was delivered. */
    data class Rejected(val reason: String) : SendResult
}

/**
 * One request carries the Email/set that files the draft and the
 * EmailSubmission/set that sends it, so a message is never submitted
 * without having been created. A refusal comes back as [SendResult.Rejected]
 * rather than a thrown error: the caller keeps the text and shows why.
 */
class ComposeRepository(
    private val jmapClient: JmapClient,
    private val mailboxes: MailboxRepository,
) {
    suspend fun identity(): Identity {
        val first =
            jmapClient.callOne("Identity/get", JsonObject(emptyMap())).getValue("list").jsonArray.firstOrNull()?.jsonObject
                ?: throw JmapException(JmapError.Request(type = "noIdentity", detail = "This account has no sending identity."))
        return Identity(
            id = first.getValue("id").jsonPrimitive.content,
            name = first["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            email = first.getValue("email").jsonPrimitive.content,
        )
    }

    suspend fun send(
        identity: Identity,
        draft: Draft,
    ): SendResult {
        val draftsId = mailboxId("drafts")
        val sentId = mailboxId("sent")
        val outcomes =
            jmapClient.call(
                listOf(
                    Invocation("Email/set", createEmail(identity, draft, draftsId), "c0"),
                    Invocation("EmailSubmission/set", submit(identity, draftsId, sentId), "c1"),
                ),
            )
        val reason = outcomes.rejection() ?: return SendResult.Sent
        outcomes.createdDraftId()?.let { destroyDraft(it) }
        return SendResult.Rejected(reason)
    }

    private suspend fun destroyDraft(emailId: String) {
        val arguments = buildJsonObject { putJsonArray("destroy") { add(JsonPrimitive(emailId)) } }
        jmapClient.callOne("Email/set", arguments)
    }

    private suspend fun mailboxId(role: String): String =
        mailboxes.findMailboxByRole(role)?.id
            ?: throw JmapException(JmapError.Request(type = "missingMailbox", detail = role))
}

private fun createEmail(
    identity: Identity,
    draft: Draft,
    draftsId: String,
): JsonObject =
    buildJsonObject {
        putJsonObject("create") {
            putJsonObject("draft") {
                putJsonObject("mailboxIds") { put(draftsId, true) }
                putJsonObject("keywords") { put("\$draft", true) }
                putAddresses("from", listOf(formatAddress(identity)))
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

private fun submit(
    identity: Identity,
    draftsId: String,
    sentId: String,
): JsonObject =
    buildJsonObject {
        putJsonObject("create") {
            putJsonObject("submission") {
                put("identityId", identity.id)
                put("emailId", "#draft")
            }
        }
        putJsonObject("onSuccessUpdateEmail") {
            putJsonObject("#submission") {
                put("mailboxIds/$draftsId", JsonNull)
                put("mailboxIds/$sentId", true)
                put("keywords/\$draft", JsonNull)
            }
        }
    }

private fun JsonObjectBuilder.putAddresses(
    key: String,
    addresses: List<String>,
) {
    if (addresses.isEmpty()) return
    putJsonArray(key) {
        addresses.forEach { address ->
            addJsonObject {
                val name = nameOf(address)
                if (name.isNotEmpty()) put("name", name)
                put("email", emailOf(address))
            }
        }
    }
}

private fun formatAddress(identity: Identity) = formatAddress(identity.name.ifEmpty { null }, identity.email)

private fun Map<String, MethodOutcome>.rejection(): String? {
    val email = getValue("c0")
    val submission = getValue("c1")
    if (email is MethodOutcome.Failure) return email.error.reason
    (email as MethodOutcome.Success).result.setErrorReason("notCreated", "draft")?.let { return it }
    if (submission is MethodOutcome.Failure) return submission.error.reason
    return (submission as MethodOutcome.Success).result.setErrorReason("notCreated", "submission")
}

private fun Map<String, MethodOutcome>.createdDraftId(): String? =
    (getValue("c0") as? MethodOutcome.Success)?.result?.get("created")?.jsonObject?.get("draft")?.jsonObject
        ?.get("id")?.jsonPrimitive?.content
