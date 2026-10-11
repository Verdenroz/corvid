package com.verdenroz.corvid.core.jmap

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readLine
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.seconds

private const val TYPES = "Email,Mailbox,Thread,EmailDelivery"
private const val PING_SECONDS = 30

/** RFC 8620 section 7.1: the new state of every changed data type, per account. */
@Serializable
data class StateChange(val changed: Map<String, Map<String, String>>) {
    fun touches(vararg types: String): Boolean = changed.values.any { states -> types.any { it in states } }
}

data class ServerEvent(val name: String, val data: String, val id: String? = null)

/** The `text/event-stream` line grammar: a blank line ends an event, `:` starts a comment, repeated `data` lines join with a newline. */
class EventParser {
    private var name = "message"
    private var id: String? = null
    private val data = mutableListOf<String>()

    fun line(line: String): ServerEvent? {
        if (line.isEmpty()) {
            val event = if (data.isEmpty()) null else ServerEvent(name, data.joinToString("\n"), id)
            name = "message"
            id = null
            data.clear()
            return event
        }
        if (line.startsWith(':')) return null
        val value = line.substringAfter(':', "").removePrefix(" ")
        when (line.substringBefore(':')) {
            "event" -> name = value
            "id" -> id = value
            "data" -> data += value
        }
        return null
    }
}

/**
 * One connection at a time, reopened with [ReconnectPolicy] backoff whenever it drops or goes silent
 * for twice the server's ping interval. A terminal error (401, 403, a rejected request, TLS) ends the flow.
 */
internal fun stateChangeFlow(
    httpClient: HttpClient,
    authLatch: AuthLatch,
    eventSourceUrl: () -> String,
    bearerToken: suspend () -> String?,
): Flow<StateChange> =
    flow {
        val policy = ReconnectPolicy()
        while (true) {
            try {
                streamEvents(httpClient, authLatch, eventSourceUrl(), bearerToken, policy)
            } catch (e: JmapException) {
                if (!e.error.retryable) throw e
            }
            delay(policy.nextDelay())
        }
    }

private val JmapError.retryable get() = this is JmapError.Unreachable || this is JmapError.Server || this is JmapError.RateLimited

private suspend fun FlowCollector<StateChange>.streamEvents(
    httpClient: HttpClient,
    authLatch: AuthLatch,
    template: String,
    bearerToken: suspend () -> String?,
    policy: ReconnectPolicy,
) {
    val url = template.replace("{types}", TYPES).replace("{closeafter}", "no").replace("{ping}", PING_SECONDS.toString())
    transport(authLatch) {
        httpClient.prepareGet(url) {
            bearerToken()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            header(HttpHeaders.Accept, "text/event-stream")
            timeout {
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            }
        }.execute { response ->
            rejectFailure(authLatch, response)
            val channel = response.bodyAsChannel()
            val parser = EventParser()
            var pingSeconds = PING_SECONDS
            while (true) {
                val line =
                    try {
                        withTimeout((2 * pingSeconds).seconds) { channel.readLine() }
                    } catch (e: TimeoutCancellationException) {
                        return@execute
                    } ?: return@execute
                val event = parser.line(line) ?: continue
                policy.reset()
                when (event.name) {
                    "ping" -> pingSeconds = pingInterval(event.data) ?: pingSeconds
                    "state" -> emit(stateChange(event.data))
                }
            }
        }
    }
}

private fun pingInterval(data: String): Int? =
    try {
        JmapJson.parseToJsonElement(data).jsonObject["interval"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.takeIf { it > 0 }
    } catch (e: SerializationException) {
        null
    }

private fun stateChange(data: String): StateChange =
    try {
        JmapJson.decodeFromString<StateChange>(data)
    } catch (e: SerializationException) {
        throw JmapException(JmapError.Request(type = "notJMAP", detail = e.message))
    }
