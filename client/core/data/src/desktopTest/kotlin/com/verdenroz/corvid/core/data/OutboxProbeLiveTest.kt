package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.jmap.AuthLatch
import com.verdenroz.corvid.core.jmap.Invocation
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.MethodOutcome
import com.verdenroz.corvid.core.jmap.callOne
import com.verdenroz.corvid.core.jmap.createJmapHttpClient
import com.verdenroz.corvid.core.model.Draft
import com.verdenroz.corvid.core.model.EmailAddress
import com.verdenroz.corvid.core.model.Identity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test

/**
 * Measures, against a running Stalwart, the JMAP behaviour an offline outbox would lean on: a
 * client-chosen Message-ID, finding mail by it, creating and submitting in one request, and
 * submitting twice. It asserts nothing; every request and response is printed under a Q label.
 * It runs only with CORVID_OUTBOX_LIVE=1, reading the origin from CORVID_LIVE_URL, a bearer token
 * for the mailbox from CORVID_LIVE_TOKEN and that mailbox's address from CORVID_LIVE_ADDRESS, which
 * receives its own mail.
 *
 *   CORVID_OUTBOX_LIVE=1 CORVID_LIVE_URL=... ./gradlew :core:data:desktopTest --rerun \
 *     --tests '*OutboxProbeLiveTest*' -i
 */
class OutboxProbeLiveTest {
    @Test
    fun `the outbox's Message-ID probe and single request send are measured against a live server`() {
        if (System.getenv("CORVID_OUTBOX_LIVE") != "1") {
            println("skipped: set CORVID_OUTBOX_LIVE=1 against a running rig to run this")
            return
        }
        val url = env("CORVID_LIVE_URL")
        val token = env("CORVID_LIVE_TOKEN")
        val address = env("CORVID_LIVE_ADDRESS")

        runBlocking {
            val client = connect(url, token)
            val boxes = mailboxIds(client)
            val identity = identityOf(client, address)
            val stamp = System.currentTimeMillis()

            println("== Q1 a client-supplied Message-ID on Email/set create")
            val viaProperty = "q1-property-$stamp@probe.test"
            val viaHeader = "q1-header-$stamp@probe.test"
            val marker = "outbox-$stamp"
            val markerKeywords =
                buildJsonObject {
                    put("\$draft", true)
                    put("\$seen", true)
                    put(marker, true)
                }
            val propertyCall =
                draftCall(identity, boxes, address, "q1 property $stamp", "messageId" to ids(viaProperty), "keywords" to markerKeywords)
            val headerCall = draftCall(identity, boxes, address, "q1 header $stamp", "header:Message-ID:asMessageIds" to ids(viaHeader))
            val propertyId = created(client, "Q1 messageId property", propertyCall)
            val headerId = created(client, "Q1 header:Message-ID:asMessageIds", headerCall)
            readIds(client, "Q1 read back (property)", propertyId)
            trace(client, "Q1 keywords read back", Invocation("Email/get", get(propertyId, "keywords"), "g"))
            readIds(client, "Q1 read back (header)", headerId)

            println("== Q2 Email/query with a Message-ID header filter")
            val draftsOnly = mapOf("inMailbox" to boxes.getValue("drafts"))
            find(client, "Q2 exact, in Drafts", "<$viaProperty>", draftsOnly)
            find(client, "Q2 exact, no mailbox filter", "<$viaProperty>")
            find(client, "Q2 exact without angle brackets", viaProperty)
            find(client, "Q2 substring of the local part", "q1-property-")
            find(client, "Q2 upper-cased", "<${viaProperty.uppercase()}>")
            find(client, "Q2 another message's id", "<$viaHeader>")
            byKeyword(client, "Q2b hasKeyword, in Drafts", marker, draftsOnly)
            byKeyword(client, "Q2b hasKeyword, no mailbox filter", marker)
            scan(client, "Q2c scan, no mailbox filter", viaProperty, boxes)
            scan(client, "Q2c scan, another message's id", viaHeader, boxes)
            submitOnly(client, "Q2 submit (separate request)", identity, propertyId, boxes)
            settle()
            byKeyword(client, "Q2b hasKeyword after submit, in Sent", marker, mapOf("inMailbox" to boxes.getValue("sent")))
            byKeyword(client, "Q2b hasKeyword after submit, no mailbox filter", marker)
            scan(client, "Q2c scan after submit", viaProperty, boxes)
            find(client, "Q2 after submit, in Sent", "<$viaProperty>", mapOf("inMailbox" to boxes.getValue("sent")))
            find(client, "Q2 after submit, in Drafts", "<$viaProperty>", draftsOnly)
            find(client, "Q2 after submit, in Inbox", "<$viaProperty>", mapOf("inMailbox" to boxes.getValue("inbox")))
            find(client, "Q2 after submit, no mailbox filter", "<$viaProperty>")

            println("== Q3 create and submit in one request, sent twice")
            val single = "q3-single-$stamp@probe.test"
            repeat(2) { attempt ->
                val create = draftCall(identity, boxes, address, "q3 single $stamp", "messageId" to ids(single))
                trace(
                    client,
                    "Q3 attempt ${attempt + 1}",
                    Invocation("Email/set", create, "e"),
                    Invocation("EmailSubmission/set", submit(identity, "#draft", boxes.getValue("drafts"), boxes.getValue("sent")), "s"),
                )
                settle()
                find(client, "Q3 after attempt ${attempt + 1}, header filter", "<$single>")
                scan(client, "Q3 after attempt ${attempt + 1}, scan", single, boxes)
            }

            println("== Q4 EmailSubmission/query and /get")
            val sentSingle = scan(client, "Q4 locate", single, boxes).getValue("sent").first()
            trace(
                client,
                "Q4 submitted email",
                Invocation("EmailSubmission/query", buildJsonObject { putJsonObject("filter") { put("emailIds", ids(sentSingle)) } }, "q"),
                Invocation("EmailSubmission/get", backRef("q"), "g"),
            )
            trace(
                client,
                "Q4 never-submitted draft",
                Invocation("EmailSubmission/query", buildJsonObject { putJsonObject("filter") { put("emailIds", ids(headerId)) } }, "q"),
                Invocation("EmailSubmission/get", backRef("q"), "g"),
            )

            println("== Q5 submitting an email that is already in Sent")
            val before = scan(client, "Q5 before", single, boxes).getValue("inbox").size
            trace(
                client,
                "Q5 resubmit, no update",
                Invocation("EmailSubmission/set", resubmit(identity, sentSingle), "s"),
            )
            settle()
            val after = scan(client, "Q5 after", single, boxes).getValue("inbox").size
            println("Q5 delivered copies: $before -> $after")

            println("== Q6 state read back by a client that never saw the response")
            val lost = "q6-lost-$stamp@probe.test"
            val lostCreate = draftCall(identity, boxes, address, "q6 lost $stamp", "messageId" to ids(lost))
            trace(
                client,
                "Q6 send whose response is discarded",
                Invocation("Email/set", lostCreate, "e"),
                Invocation("EmailSubmission/set", submit(identity, "#draft", boxes.getValue("drafts"), boxes.getValue("sent")), "s"),
            )
            settle()
            val fresh = connect(url, token)
            val lostId = scan(fresh, "Q6 fresh client locate", lost, boxes).getValue("sent").first()
            trace(
                fresh,
                "Q6 fresh client Email/get",
                Invocation("Email/get", get(lostId, "mailboxIds", "keywords", "messageId"), "g"),
            )
        }
    }

    private fun env(name: String): String = checkNotNull(System.getenv(name)) { "$name is not set" }

    private suspend fun connect(
        url: String,
        token: String,
    ): JmapClient = JmapClient.connect(createJmapHttpClient(), AuthLatch(), url) { token }

    private suspend fun settle() = delay(2000)

    private fun ids(vararg values: String): JsonArray = JsonArray(values.map(::JsonPrimitive))

    private fun backRef(callId: String): JsonObject =
        buildJsonObject {
            putJsonObject("#ids") {
                put("resultOf", callId)
                put("name", "EmailSubmission/query")
                put("path", "/ids")
            }
        }

    private fun get(
        id: String,
        vararg properties: String,
    ): JsonObject =
        buildJsonObject {
            put("ids", ids(id))
            put("properties", ids(*properties))
        }

    private suspend fun mailboxIds(client: JmapClient): Map<String, String> =
        client.callOne("Mailbox/get", buildJsonObject { put("properties", ids("role")) })
            .getValue("list").jsonArray.map { it.jsonObject }
            .filter { it.getValue("role") is JsonPrimitive && it.getValue("role").jsonPrimitive.content != "null" }
            .associate { it.getValue("role").jsonPrimitive.content to it.getValue("id").jsonPrimitive.content }

    private suspend fun identityOf(
        client: JmapClient,
        address: String,
    ): Identity =
        client.callOne("Identity/get", buildJsonObject { }).getValue("list").jsonArray.map { it.jsonObject }
            .first { it.getValue("email").jsonPrimitive.content == address }
            .let { Identity(it.getValue("id").jsonPrimitive.content, it.getValue("name").jsonPrimitive.content, address) }

    private fun draftCall(
        identity: Identity,
        boxes: Map<String, String>,
        to: String,
        subject: String,
        vararg extra: Pair<String, JsonElement>,
    ): JsonObject {
        val create =
            createEmail(identity, Draft(to = listOf(EmailAddress(null, to)), subject = subject, body = "probe"), boxes.getValue("drafts"))
        val draft = create.getValue("create").jsonObject.getValue("draft").jsonObject
        return JsonObject(mapOf("create" to JsonObject(mapOf("draft" to JsonObject(draft + extra.toMap())))))
    }

    private fun resubmit(
        identity: Identity,
        emailId: String,
    ): JsonObject =
        buildJsonObject {
            putJsonObject("create") {
                putJsonObject("submission") {
                    put("identityId", identity.id)
                    put("emailId", emailId)
                }
            }
        }

    /** Runs [calls] as one request and prints each call and its outcome. */
    private suspend fun trace(
        client: JmapClient,
        label: String,
        vararg calls: Invocation,
    ): Map<String, MethodOutcome> {
        calls.forEach { println("$label REQUEST ${it.name} ${it.arguments}") }
        val outcomes = client.call(calls.toList())
        outcomes.forEach { (callId, outcome) ->
            when (outcome) {
                is MethodOutcome.Success -> println("$label RESPONSE $callId ${outcome.result}")
                is MethodOutcome.Failure -> println("$label ERROR $callId ${outcome.error}")
            }
        }
        delay(300)
        return outcomes
    }

    private suspend fun created(
        client: JmapClient,
        label: String,
        create: JsonObject,
    ): String {
        val result = trace(client, label, Invocation("Email/set", create, "e")).getValue("e") as MethodOutcome.Success
        return result.result.getValue("created").jsonObject.getValue("draft").jsonObject.getValue("id").jsonPrimitive.content
    }

    private suspend fun readIds(
        client: JmapClient,
        label: String,
        id: String,
    ) = trace(
        client,
        label,
        Invocation(
            "Email/get",
            get(id, "messageId", "header:Message-ID", "header:Message-ID:asMessageIds", "header:Message-ID:asText"),
            "g",
        ),
    )

    private suspend fun submitOnly(
        client: JmapClient,
        label: String,
        identity: Identity,
        emailId: String,
        boxes: Map<String, String>,
    ) = trace(
        client,
        label,
        Invocation("EmailSubmission/set", submit(identity, emailId, boxes.getValue("drafts"), boxes.getValue("sent")), "s"),
    )

    private suspend fun find(
        client: JmapClient,
        label: String,
        value: String,
        extra: Map<String, String> = emptyMap(),
    ) = trace(client, label, Invocation("Email/query", queryFor(value, extra), "q"))

    private fun queryFor(
        value: String,
        extra: Map<String, String>,
    ): JsonObject =
        buildJsonObject {
            putJsonObject("filter") {
                put("header", ids("Message-ID", value))
                extra.forEach { (key, id) -> put(key, id) }
            }
        }

    private suspend fun byKeyword(
        client: JmapClient,
        label: String,
        keyword: String,
        extra: Map<String, String> = emptyMap(),
    ) = trace(
        client,
        label,
        Invocation(
            "Email/query",
            buildJsonObject {
                putJsonObject("filter") {
                    put("hasKeyword", keyword)
                    extra.forEach { (key, id) -> put(key, id) }
                }
            },
            "q",
        ),
    )

    /** Finds mail by reading every Message-ID, since the server does not index the header; returns the matches' ids by mailbox role. */
    private suspend fun scan(
        client: JmapClient,
        label: String,
        messageId: String,
        boxes: Map<String, String>,
    ): Map<String, List<String>> {
        val query = Invocation("Email/query", buildJsonObject { put("limit", 500) }, "q")
        val read =
            Invocation(
                "Email/get",
                buildJsonObject {
                    putJsonObject("#ids") {
                        put("resultOf", "q")
                        put("name", "Email/query")
                        put("path", "/ids")
                    }
                    put("properties", ids("header:Message-ID:asMessageIds", "mailboxIds", "keywords"))
                },
                "g",
            )
        println("$label REQUEST ${query.name} ${query.arguments}")
        println("$label REQUEST ${read.name} ${read.arguments}")
        val list =
            (
                client.call(
                    listOf(query, read),
                ).getValue("g") as MethodOutcome.Success
            ).result.getValue("list").jsonArray.map { it.jsonObject }
        val matches =
            list.filter {
                messageId in
                    it.getValue("header:Message-ID:asMessageIds").jsonArray.map {
                            id ->
                        id.jsonPrimitive.content
                    }
            }
        println("$label RESPONSE scanned ${list.size} emails, matching: ${JsonArray(matches)}")
        delay(300)
        return boxes.mapValues {
                (_, box) ->
            matches.filter { box in it.getValue("mailboxIds").jsonObject }.map { it.getValue("id").jsonPrimitive.content }
        }
    }
}
