package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.jmap.Invocation
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.MethodOutcome
import com.verdenroz.corvid.core.jmap.callOne
import com.verdenroz.corvid.core.jmap.reason
import com.verdenroz.corvid.core.jmap.resultOrThrow
import com.verdenroz.corvid.core.jmap.setErrorReason
import com.verdenroz.corvid.core.model.ComposeIntent
import com.verdenroz.corvid.core.model.Draft
import com.verdenroz.corvid.core.model.Identity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonArray

/** Why this account cannot send at all, as opposed to a server refusing one message. */
sealed interface SendUnavailable : StartResult, SendResult {
    data object NoIdentity : SendUnavailable

    data class NoMailbox(val role: String) : SendUnavailable
}

sealed interface StartResult {
    data class Ready(val draft: Draft) : StartResult
}

sealed interface SendResult {
    data object Sent : SendResult

    /** The server refused the draft or its submission; nothing was delivered. */
    data class Rejected(val reason: String) : SendResult
}

private sealed interface Loaded {
    data class Ready(val identity: Identity, val draftsId: String, val sentId: String) : Loaded

    data class Unavailable(val reason: SendUnavailable) : Loaded
}

private inline fun <R> Loaded.fold(
    onUnavailable: (SendUnavailable) -> R,
    onReady: (Loaded.Ready) -> R,
): R =
    when (this) {
        is Loaded.Unavailable -> onUnavailable(reason)
        is Loaded.Ready -> onReady(this)
    }

/**
 * The identity and the Drafts and Sent mailboxes are fetched once, in one
 * request, and kept for as long as the repository lives. One request then
 * carries the Email/set that files the draft and the EmailSubmission/set that
 * sends it, so a message is never submitted without having been created. A
 * refusal comes back as [SendResult.Rejected] rather than a thrown error: the
 * caller keeps the text and shows why.
 */
class ComposeRepository(private val jmapClient: JmapClient) {
    private val mutex = Mutex()
    private var ready: Loaded.Ready? = null

    suspend fun start(intent: ComposeIntent): StartResult =
        load().fold({ it }) { sending ->
            StartResult.Ready(
                when (intent) {
                    is ComposeIntent.New -> Draft()
                    is ComposeIntent.Reply -> replyDraft(intent.original, sending.identity.email, intent.all)
                    is ComposeIntent.Forward -> forwardDraft(intent.original)
                },
            )
        }

    suspend fun send(draft: Draft): SendResult = load().fold({ it }) { transmit(it, draft) }

    private suspend fun transmit(
        sending: Loaded.Ready,
        draft: Draft,
    ): SendResult {
        val outcomes =
            jmapClient.call(
                listOf(
                    Invocation("Email/set", createEmail(sending.identity, draft, sending.draftsId), "c0"),
                    Invocation("EmailSubmission/set", submit(sending.identity, sending.draftsId, sending.sentId), "c1"),
                ),
            )
        val reason = outcomes.rejection() ?: return SendResult.Sent
        outcomes.createdDraftId()?.let { destroyDraft(it) }
        return SendResult.Rejected(reason)
    }

    private suspend fun load(): Loaded =
        mutex.withLock {
            ready ?: fetch().also { if (it is Loaded.Ready) ready = it }
        }

    private suspend fun fetch(): Loaded {
        val outcomes =
            jmapClient.call(
                listOf(
                    Invocation("Identity/get", JsonObject(emptyMap()), "c0"),
                    Invocation(
                        "Mailbox/get",
                        buildJsonObject { putJsonArray("properties") { listOf("id", "role").forEach { add(JsonPrimitive(it)) } } },
                        "c1",
                    ),
                ),
            )
        val identity =
            outcomes.getValue("c0").resultOrThrow().getValue("list").jsonArray.firstOrNull()?.jsonObject?.toIdentity()
                ?: return Loaded.Unavailable(SendUnavailable.NoIdentity)
        val roles =
            outcomes.getValue("c1").resultOrThrow().getValue("list").jsonArray
                .map { it.jsonObject }
                .associate { it["role"]?.jsonPrimitive?.contentOrNull to it.getValue("id").jsonPrimitive.content }
        val draftsId = roles["drafts"] ?: return Loaded.Unavailable(SendUnavailable.NoMailbox("drafts"))
        val sentId = roles["sent"] ?: return Loaded.Unavailable(SendUnavailable.NoMailbox("sent"))
        return Loaded.Ready(identity, draftsId, sentId)
    }

    private suspend fun destroyDraft(emailId: String) {
        val arguments = buildJsonObject { putJsonArray("destroy") { add(JsonPrimitive(emailId)) } }
        jmapClient.callOne("Email/set", arguments)
    }
}

private fun JsonObject.toIdentity() =
    Identity(
        id = getValue("id").jsonPrimitive.content,
        name = get("name")?.jsonPrimitive?.contentOrNull.orEmpty(),
        email = getValue("email").jsonPrimitive.content,
    )

private fun Map<String, MethodOutcome>.rejection(): String? = getValue("c0").refusal("draft") ?: getValue("c1").refusal("submission")

private fun MethodOutcome.refusal(creationId: String): String? =
    when (this) {
        is MethodOutcome.Failure -> error.reason
        is MethodOutcome.Success -> result.setErrorReason("notCreated", creationId)
    }

private fun Map<String, MethodOutcome>.createdDraftId(): String? =
    (getValue("c0") as? MethodOutcome.Success)?.result?.get("created")?.jsonObject?.get("draft")?.jsonObject
        ?.get("id")?.jsonPrimitive?.content
