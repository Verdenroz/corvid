package com.verdenroz.corvid.core.data.outbox

import com.verdenroz.corvid.core.data.ComposeRepository
import com.verdenroz.corvid.core.data.Route
import com.verdenroz.corvid.core.data.createEmail
import com.verdenroz.corvid.core.data.findByKeyword
import com.verdenroz.corvid.core.data.submit
import com.verdenroz.corvid.core.data.tidySent
import com.verdenroz.corvid.core.jmap.Invocation
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.JmapError
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.jmap.MethodOutcome
import com.verdenroz.corvid.core.jmap.callOne
import com.verdenroz.corvid.core.jmap.reason
import com.verdenroz.corvid.core.jmap.resultOrThrow
import com.verdenroz.corvid.core.model.CorvidKeywords
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.net.ssl.SSLHandshakeException
import kotlin.time.Clock

sealed interface ItemResult {
    data object Sent : ItemResult

    /** [reason] is the server's text and stays in memory; only [category] is written to disk. */
    data class Rejected(val category: RejectionCategory, val reason: String) : ItemResult

    /**
     * The server may or may not hold the message, and it will not be sent again on its own.
     * [transportFailed] is set when the request itself failed, which means the network is not worth trying again yet.
     */
    data class Unconfirmed(val transportFailed: Boolean) : ItemResult

    /** Nothing reached the server, or it refused before acting; the item will be tried again. */
    data object Deferred : ItemResult

    data object SignedOut : ItemResult

    data class KeyMismatch(val sealedTo: String) : ItemResult

    data object Unreadable : ItemResult
}

data class Drained(val id: String, val result: ItemResult)

private sealed interface Probe {
    data class Delivered(val emailId: String) : Probe

    data class Staged(val emailId: String) : Probe

    data object Ambiguous : Probe

    data object None : Probe
}

private sealed interface Opening {
    class Opened(val message: OutboxMessage) : Opening

    class Failed(val result: ItemResult) : Opening
}

private enum class Phase { Reading, Sending }

private class Refusal(val type: String, val reason: String) {
    val permanent: Boolean
        get() = type.startsWith("forbidden") || type.startsWith("invalid") || type in PERMANENT

    private companion object {
        val PERMANENT =
            setOf("noRecipients", "tooManyRecipients", "tooLarge", "overQuota", "notFound", "accountReadOnly", "accountNotFound")
    }
}

/**
 * Sends the queued messages, oldest first, one at a time. A message is never submitted again unless
 * the server is shown not to hold it: a retry first looks for the message by its keyword, and an
 * outcome that cannot be told apart from a success is [ItemResult.Unconfirmed], not a resend.
 */
class OutboxSender(
    private val store: OutboxStore,
    private val jmapClient: JmapClient,
    private val compose: ComposeRepository,
    private val deviceKey: ByteArray,
    private val clock: Clock = Clock.System,
) {
    private val mutex = Mutex()

    suspend fun drain(): List<Drained> =
        mutex.withLock {
            val items = store.list().items
            items.filter { (it.state as? OutboxState.Sent)?.tidy != null }.forEach { tidy(it) }
            val drained = mutableListOf<Drained>()
            for (item in items.filter { it.state.isPending }) {
                val result = send(item)
                drained += Drained(item.id, result)
                if (result.stopsDrain) break
            }
            drained
        }

    /**
     * The user's decision to send an [OutboxState.Unconfirmed] message again, accepting a possible duplicate,
     * or a [OutboxState.Rejected] one, which the server is known not to have sent.
     */
    suspend fun resend(id: String) {
        mutex.withLock {
            store.read(id)?.takeIf { it.state == OutboxState.Unconfirmed || it.state is OutboxState.Rejected }
                ?.let { store.save(it.copy(state = OutboxState.Queued)) }
        }
    }

    /**
     * Deletes a message the user gives up on: one that was rejected, is unconfirmed or cannot be read.
     * An unconfirmed message may already have been sent, and discarding it forgets that it might have been.
     * Returns false for anything else, sent tombstones and readable queued messages included.
     */
    suspend fun discard(id: String): Boolean =
        mutex.withLock {
            val item = store.read(id) ?: return@withLock false
            val discardable =
                when (item.state) {
                    is OutboxState.Rejected, is OutboxState.Unconfirmed -> true
                    is OutboxState.Sent -> false
                    else -> open(item) is Opening.Failed
                }
            discardable.also { if (it) store.remove(id) }
        }

    private fun open(item: OutboxItem): Opening {
        val sealed = (store.readMessage(item.id) as? MessageRead.Found)?.message ?: return Opening.Failed(ItemResult.Unreadable)
        return when (val opened = OutboxSealer.open(item.id, sealed, deviceKey)) {
            is OpenResult.Opened -> Opening.Opened(opened.message)
            is OpenResult.KeyMismatch -> Opening.Failed(ItemResult.KeyMismatch(opened.sealedTo))
            is OpenResult.Corrupt -> Opening.Failed(ItemResult.Unreadable)
        }
    }

    private suspend fun send(item: OutboxItem): ItemResult {
        val message =
            when (val opening = open(item)) {
                is Opening.Opened -> opening.message
                is Opening.Failed -> return opening.result
            }
        val recovering = item.state == OutboxState.Unconfirmed || (item.state as? OutboxState.Submitting)?.recovering == true
        val submitting = OutboxState.Submitting(clock.now().toEpochMilliseconds(), recovering)
        val started = item.copy(attempt = item.attempt + 1, state = submitting)
        store.save(started)
        return Attempt(item, started, message, recovering).run()
    }

    private suspend fun tidy(item: OutboxItem) {
        val sent = item.state as OutboxState.Sent
        val pending = sent.tidy ?: return
        val payload = tidySent(sent.serverEmailId, pending.keyword, pending.replacesDraftId)
        val reply = bestEffort { jmapClient.callOne("Email/set", payload) } ?: return
        val left =
            Tidy(
                keyword = pending.keyword.takeUnless { reply.updated(sent.serverEmailId) },
                replacesDraftId = pending.replacesDraftId?.takeUnless { reply.destroyed(it) },
            )
        store.save(item.copy(state = sent.copy(tidy = left.takeUnless { it.keyword == null && it.replacesDraftId == null })))
    }

    private inner class Attempt(
        private val original: OutboxItem,
        private val started: OutboxItem,
        private val message: OutboxMessage,
        private val recovering: Boolean,
    ) {
        private val untouched = original.state == OutboxState.Queued && original.attempt == 0

        suspend fun run(): ItemResult {
            val route =
                try {
                    compose.routeFor(message.draft.from)
                } catch (e: JmapException) {
                    return settle(e.error, Phase.Reading)
                }
            if (route !is Route.Ready) return refuse(route)
            val found =
                if (untouched) {
                    Probe.None
                } else {
                    try {
                        probe(route)
                    } catch (e: JmapException) {
                        return settle(e.error, Phase.Reading)
                    }
                }
            return when (found) {
                is Probe.Delivered -> finish(found.emailId)
                is Probe.Staged -> submitExisting(route, found.emailId)
                is Probe.Ambiguous -> unconfirmed(transportFailed = false)
                is Probe.None -> if (recovering) unconfirmed(transportFailed = false) else createAndSubmit(route)
            }
        }

        private suspend fun probe(route: Route.Ready): Probe {
            val reply = jmapClient.call(findByKeyword(message.keyword)).getValue("read").resultOrThrow()
            val copies =
                try {
                    reply.getValue("list").jsonArray.map { it.jsonObject }.map {
                        Copy(it.id(), it.isIn(route.draftsId), it.hasKeyword(CorvidKeywords.DRAFT))
                    }
                } catch (e: IllegalArgumentException) {
                    throw JmapException(JmapError.Request("unreadableProbe", e.message))
                } catch (e: NoSuchElementException) {
                    throw JmapException(JmapError.Request("unreadableProbe", e.message))
                }
            return when {
                copies.isEmpty() -> Probe.None
                copies.none { it.draft } -> Probe.Delivered(copies.first().id)
                copies.size == 1 && copies.single().inDrafts -> Probe.Staged(copies.single().id)
                else -> Probe.Ambiguous
            }
        }

        private suspend fun createAndSubmit(route: Route.Ready): ItemResult {
            val email = createEmail(route.identity, message.draft, route.draftsId, message.messageId, message.keyword)
            val calls =
                listOf(
                    Invocation("Email/set", email, "c0"),
                    Invocation("EmailSubmission/set", submit(route.identity, "#draft", route.draftsId, route.sentId), "c1"),
                )
            val outcomes =
                try {
                    jmapClient.call(calls, singleAttempt = true)
                } catch (e: JmapException) {
                    return settle(e.error, Phase.Sending)
                }
            outcomes.getValue("c0").refusal("draft")?.let { return refused(it) }
            outcomes.getValue("c1").refusal("submission")?.let { return refused(it) }
            val created = outcomes.getValue("c0") as MethodOutcome.Success
            return finish(created.result.getValue("created").jsonObject.getValue("draft").jsonObject.id())
        }

        private suspend fun submitExisting(
            route: Route.Ready,
            emailId: String,
        ): ItemResult {
            val call = Invocation("EmailSubmission/set", submit(route.identity, emailId, route.draftsId, route.sentId), "c0")
            val outcome =
                try {
                    jmapClient.call(listOf(call), singleAttempt = true).getValue("c0")
                } catch (e: JmapException) {
                    return settle(e.error, Phase.Sending)
                }
            return outcome.refusal("submission")?.let { refused(it) } ?: finish(emailId)
        }

        private suspend fun finish(emailId: String): ItemResult {
            val tidy = Tidy(message.keyword, message.replacesDraftId)
            val sent = started.copy(state = OutboxState.Sent(emailId, clock.now().toEpochMilliseconds(), tidy))
            store.finish(sent)
            tidy(sent)
            return ItemResult.Sent
        }

        private fun refuse(route: Route): ItemResult =
            when (route) {
                is Route.AliasMissing -> reject(RejectionCategory.AliasGone, route.email)
                is Route.Unavailable -> reject(RejectionCategory.Invalid, route.reason.toString())
                is Route.Ready -> error("a ready route is not a refusal")
            }

        private fun refused(refusal: Refusal): ItemResult =
            if (refusal.permanent) {
                reject(RejectionCategory.Refused, refusal.reason)
            } else {
                store.save(started.copy(state = OutboxState.Queued))
                ItemResult.Deferred
            }

        private fun reject(
            category: RejectionCategory,
            reason: String,
        ): ItemResult {
            store.save(started.copy(state = OutboxState.Rejected(category)))
            return ItemResult.Rejected(category, reason)
        }

        private fun unconfirmed(transportFailed: Boolean): ItemResult {
            store.save(started.copy(state = OutboxState.Unconfirmed))
            return ItemResult.Unconfirmed(transportFailed)
        }

        private fun defer(): ItemResult {
            store.save(original)
            return ItemResult.Deferred
        }

        private fun settle(
            error: JmapError,
            phase: Phase,
        ): ItemResult {
            return when (error) {
                is JmapError.Unauthorized -> {
                    store.save(original)
                    ItemResult.SignedOut
                }
                is JmapError.Forbidden ->
                    if (phase == Phase.Sending || untouched) reject(RejectionCategory.Forbidden, "forbidden") else defer()
                is JmapError.Request ->
                    if (phase == Phase.Sending || untouched) reject(RejectionCategory.Invalid, error.detail ?: error.type) else defer()
                is JmapError.RateLimited -> defer()
                is JmapError.TlsFailure ->
                    if (phase == Phase.Sending && error.cause !is SSLHandshakeException) unconfirmed(transportFailed = true) else defer()
                is JmapError.Unreachable, is JmapError.Server, is JmapError.Method ->
                    if (phase == Phase.Sending) unconfirmed(transportFailed = true) else defer()
            }
        }
    }

    private val OutboxState.isPending: Boolean
        get() = this == OutboxState.Queued || this is OutboxState.Submitting || this == OutboxState.Unconfirmed

    private val ItemResult.stopsDrain: Boolean
        get() = this == ItemResult.Deferred || this == ItemResult.SignedOut || (this is ItemResult.Unconfirmed && transportFailed)
}

private class Copy(val id: String, val inDrafts: Boolean, val draft: Boolean)

private suspend fun <T> bestEffort(block: suspend () -> T): T? =
    try {
        block()
    } catch (_: JmapException) {
        null
    }

private fun MethodOutcome.refusal(creationId: String): Refusal? =
    when (this) {
        is MethodOutcome.Failure -> Refusal(error.type, error.reason)
        is MethodOutcome.Success ->
            result["notCreated"]?.jsonObject?.get(creationId)?.jsonObject?.let {
                val type = it.getValue("type").jsonPrimitive.content
                Refusal(type, it["description"]?.jsonPrimitive?.contentOrNull ?: type)
            }
    }

private fun JsonObject.updated(id: String): Boolean = this["updated"]?.jsonObject?.containsKey(id) == true || gone("notUpdated", id)

private fun JsonObject.destroyed(id: String): Boolean =
    this["destroyed"]?.jsonArray?.any { it.jsonPrimitive.content == id } == true || gone("notDestroyed", id)

private fun JsonObject.gone(
    failed: String,
    id: String,
): Boolean = this[failed]?.jsonObject?.get(id)?.jsonObject?.get("type")?.jsonPrimitive?.content == "notFound"

private fun JsonObject.id() = getValue("id").jsonPrimitive.content

private fun JsonObject.isIn(mailboxId: String) = mailboxId in getValue("mailboxIds").jsonObject

private fun JsonObject.hasKeyword(keyword: String) = keyword in getValue("keywords").jsonObject
