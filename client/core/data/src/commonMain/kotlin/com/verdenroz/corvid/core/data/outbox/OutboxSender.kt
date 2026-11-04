package com.verdenroz.corvid.core.data.outbox

import com.verdenroz.corvid.core.data.ComposeRepository
import com.verdenroz.corvid.core.data.Route
import com.verdenroz.corvid.core.data.createEmail
import com.verdenroz.corvid.core.data.createdDraftId
import com.verdenroz.corvid.core.data.findByKeyword
import com.verdenroz.corvid.core.data.refusal
import com.verdenroz.corvid.core.data.submit
import com.verdenroz.corvid.core.data.tidySent
import com.verdenroz.corvid.core.jmap.Invocation
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.JmapError
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.jmap.callOne
import com.verdenroz.corvid.core.jmap.resultOrThrow
import com.verdenroz.corvid.core.model.CorvidKeywords
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
            val drained = mutableListOf<Drained>()
            for (item in store.list().items.filter { it.state.isPending }) {
                val result = send(item)
                drained += Drained(item.id, result)
                if (result.stopsDrain) break
            }
            drained
        }

    /** The user's decision to send an [OutboxState.Unconfirmed] message again, accepting a possible duplicate. */
    suspend fun resend(id: String) {
        mutex.withLock {
            store.list().items.firstOrNull { it.id == id && it.state == OutboxState.Unconfirmed }
                ?.let { store.save(it.copy(state = OutboxState.Queued)) }
        }
    }

    private suspend fun send(item: OutboxItem): ItemResult {
        val sealed = (store.readMessage(item.id) as? MessageRead.Found)?.message ?: return ItemResult.Unreadable
        val message =
            when (val opened = OutboxSealer.open(item.id, sealed, deviceKey)) {
                is OpenResult.Opened -> opened.message
                is OpenResult.KeyMismatch -> return ItemResult.KeyMismatch(opened.sealedTo)
                is OpenResult.Corrupt -> return ItemResult.Unreadable
            }
        val started = item.copy(attempt = item.attempt + 1, state = OutboxState.Submitting(clock.now().toEpochMilliseconds()))
        store.save(started)
        return Attempt(item, started, message).run()
    }

    private inner class Attempt(
        private val original: OutboxItem,
        private val started: OutboxItem,
        private val message: OutboxMessage,
    ) {
        suspend fun run(): ItemResult {
            val route =
                try {
                    compose.routeFor(message.draft.from)
                } catch (e: JmapException) {
                    return settle(e.error, mayHaveSent = false)
                }
            if (route !is Route.Ready) return refuse(route)
            val found =
                if (original.state == OutboxState.Queued && original.attempt == 0) {
                    Probe.None
                } else {
                    try {
                        probe(route)
                    } catch (e: JmapException) {
                        return settle(e.error, mayHaveSent = false)
                    }
                }
            return when (found) {
                is Probe.Delivered -> finish(found.emailId)
                is Probe.Staged -> submitExisting(route, found.emailId)
                is Probe.Ambiguous -> unconfirmed(transportFailed = false)
                is Probe.None ->
                    if (original.state == OutboxState.Unconfirmed) unconfirmed(transportFailed = false) else createAndSubmit(route)
            }
        }

        private suspend fun probe(route: Route.Ready): Probe {
            val found =
                jmapClient.call(findByKeyword(message.keyword)).getValue("read").resultOrThrow()
                    .getValue("list").jsonArray.map { it.jsonObject }
            found.firstOrNull { it.isIn(route.sentId) && !it.hasKeyword(CorvidKeywords.DRAFT) }
                ?.let { return Probe.Delivered(it.id()) }
            val staged = found.singleOrNull()?.takeIf { it.isIn(route.draftsId) && it.hasKeyword(CorvidKeywords.DRAFT) }
            return when {
                staged != null -> Probe.Staged(staged.id())
                found.isEmpty() -> Probe.None
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
                    jmapClient.call(calls)
                } catch (e: JmapException) {
                    return settle(e.error, mayHaveSent = true)
                }
            outcomes.getValue("c0").refusal("draft")?.let { return reject(RejectionCategory.Refused, it) }
            outcomes.getValue("c1").refusal("submission")?.let { return reject(RejectionCategory.Refused, it) }
            return finish(outcomes.getValue("c0").createdDraftId())
        }

        private suspend fun submitExisting(
            route: Route.Ready,
            emailId: String,
        ): ItemResult {
            val call = Invocation("EmailSubmission/set", submit(route.identity, emailId, route.draftsId, route.sentId), "c0")
            val outcome =
                try {
                    jmapClient.call(listOf(call)).getValue("c0")
                } catch (e: JmapException) {
                    return settle(e.error, mayHaveSent = true)
                }
            return outcome.refusal("submission")?.let { reject(RejectionCategory.Refused, it) } ?: finish(emailId)
        }

        private suspend fun finish(emailId: String): ItemResult {
            store.finish(started.copy(state = OutboxState.Sent(emailId, clock.now().toEpochMilliseconds())))
            try {
                jmapClient.callOne("Email/set", tidySent(emailId, message.keyword, message.replacesDraftId))
            } catch (_: JmapException) {
                // A stray older draft or a leftover mark is harmless; the message is already sent.
            }
            return ItemResult.Sent
        }

        private fun refuse(route: Route): ItemResult =
            when (route) {
                is Route.AliasMissing -> reject(RejectionCategory.AliasGone, route.email)
                is Route.Unavailable -> reject(RejectionCategory.Invalid, route.reason.toString())
                is Route.Ready -> error("a ready route is not a refusal")
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

        /** A message already tried once is [OutboxState.Unconfirmed] when put back, never [OutboxState.Queued]. */
        private fun defer(): ItemResult {
            store.save(if (original.state == OutboxState.Queued) original else original.copy(state = OutboxState.Unconfirmed))
            return ItemResult.Deferred
        }

        private fun settle(
            error: JmapError,
            mayHaveSent: Boolean,
        ): ItemResult =
            when (error) {
                is JmapError.Unauthorized -> {
                    store.save(original)
                    ItemResult.SignedOut
                }
                is JmapError.Forbidden -> reject(RejectionCategory.Forbidden, "forbidden")
                is JmapError.Request -> reject(RejectionCategory.Invalid, error.detail ?: error.type)
                is JmapError.RateLimited, is JmapError.TlsFailure -> defer()
                is JmapError.Unreachable, is JmapError.Server, is JmapError.Method ->
                    if (mayHaveSent) unconfirmed(transportFailed = true) else defer()
            }
    }

    private val OutboxState.isPending: Boolean
        get() = this == OutboxState.Queued || this is OutboxState.Submitting || this == OutboxState.Unconfirmed

    private val ItemResult.stopsDrain: Boolean
        get() = this == ItemResult.Deferred || this == ItemResult.SignedOut || (this is ItemResult.Unconfirmed && transportFailed)
}

private fun JsonObject.id() = getValue("id").jsonPrimitive.content

private fun JsonObject.isIn(mailboxId: String) = mailboxId in getValue("mailboxIds").jsonObject

private fun JsonObject.hasKeyword(keyword: String) = keyword in getValue("keywords").jsonObject
