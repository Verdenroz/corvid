package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.jmap.Invocation
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.MethodOutcome
import com.verdenroz.corvid.core.jmap.resultReference
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal val NEWEST_FIRST = receivedAtSort(ascending = false)

internal fun receivedAtSort(ascending: Boolean): JsonArray =
    buildJsonArray {
        addJsonObject {
            put("property", "receivedAt")
            put("isAscending", ascending)
        }
    }

/** An RFC 8621 FilterOperator: AND, OR or NOT over [conditions]. */
internal fun operator(
    name: String,
    conditions: List<JsonObject>,
): JsonObject =
    buildJsonObject {
        put("operator", name)
        putJsonArray("conditions") { conditions.forEach { add(it) } }
    }

/** A FilterCondition on one property. */
internal fun condition(
    property: String,
    value: String,
): JsonObject = buildJsonObject { put(property, value) }

internal class QueryAndGet(val query: MethodOutcome, val get: MethodOutcome)

/** An Email/query and an Email/get of the ids it found, in one request; the server resolves the ids itself. */
internal suspend fun JmapClient.queryAndGet(
    query: JsonObject,
    properties: List<String>,
): QueryAndGet {
    val get =
        buildJsonObject {
            put("#ids", resultReference(resultOf = "c0", name = "Email/query", path = "/ids"))
            put("properties", JsonArray(properties.map(::JsonPrimitive)))
        }
    val outcomes = call(listOf(Invocation("Email/query", query, "c0"), Invocation("Email/get", get, "c1")))
    return QueryAndGet(outcomes.getValue("c0"), outcomes.getValue("c1"))
}

internal class ConversationQuery(
    val query: MethodOutcome,
    val latest: MethodOutcome,
    val threads: MethodOutcome,
    val members: MethodOutcome,
)

/**
 * [queryAndGet] followed by the threads of what it found and their messages' keywords, still one request.
 * The query should collapse threads, so each thread is listed once by its newest match.
 */
internal suspend fun JmapClient.queryConversations(
    query: JsonObject,
    properties: List<String>,
): ConversationQuery {
    val latest =
        buildJsonObject {
            put("#ids", resultReference(resultOf = "c0", name = "Email/query", path = "/ids"))
            put("properties", JsonArray(properties.map(::JsonPrimitive)))
        }
    val threads =
        buildJsonObject {
            put("#ids", resultReference(resultOf = "c1", name = "Email/get", path = "/list/*/threadId"))
        }
    val members =
        buildJsonObject {
            put("#ids", resultReference(resultOf = "c2", name = "Thread/get", path = "/list/*/emailIds"))
            put("properties", JsonArray(listOf(JsonPrimitive("keywords"))))
        }
    val invocations =
        listOf(
            Invocation("Email/query", query, "c0"),
            Invocation("Email/get", latest, "c1"),
            Invocation("Thread/get", threads, "c2"),
            Invocation("Email/get", members, "c3"),
        )
    val outcomes = call(invocations)
    return ConversationQuery(outcomes.getValue("c0"), outcomes.getValue("c1"), outcomes.getValue("c2"), outcomes.getValue("c3"))
}

internal class ThreadFetch(val email: MethodOutcome, val thread: MethodOutcome, val messages: MethodOutcome)

/** The thread holding [emailId] and every message in it, in one request; [messageArguments] shape the final Email/get. */
internal suspend fun JmapClient.fetchThread(
    emailId: String,
    messageArguments: JsonObject,
): ThreadFetch {
    val email =
        buildJsonObject {
            put("ids", JsonArray(listOf(JsonPrimitive(emailId))))
            put("properties", JsonArray(listOf(JsonPrimitive("threadId"))))
        }
    val thread =
        buildJsonObject {
            put("#ids", resultReference(resultOf = "c0", name = "Email/get", path = "/list/*/threadId"))
        }
    val messages =
        buildJsonObject {
            put("#ids", resultReference(resultOf = "c1", name = "Thread/get", path = "/list/*/emailIds"))
            messageArguments.forEach { (key, value) -> put(key, value) }
        }
    val invocations =
        listOf(
            Invocation("Email/get", email, "c0"),
            Invocation("Thread/get", thread, "c1"),
            Invocation("Email/get", messages, "c2"),
        )
    val outcomes = call(invocations)
    return ThreadFetch(outcomes.getValue("c0"), outcomes.getValue("c1"), outcomes.getValue("c2"))
}
