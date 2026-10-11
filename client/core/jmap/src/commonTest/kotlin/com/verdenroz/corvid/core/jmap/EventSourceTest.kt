package com.verdenroz.corvid.core.jmap

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private const val TEMPLATE = "http://host.test/jmap/eventsource/?types={types}&closeafter={closeafter}&ping={ping}"
private const val EMAIL_CHANGE = """event: state
data: {"@type":"StateChange","changed":{"u1":{"Email":"s2","Thread":"t2"}}}

"""
private const val MAILBOX_CHANGE = """event: state
data: {"@type":"StateChange","changed":{"u1":{"Mailbox":"m2"}}}

"""
private const val PING = "event: ping\ndata: {\"interval\": 30}\n\n"

class EventParserTest {
    private fun parse(vararg lines: String) = EventParser().let { parser -> lines.mapNotNull(parser::line) }

    @Test
    fun `a blank line ends an event carrying its name and data`() {
        val events = parse("event: ping", "data: {\"interval\": 30}", "")

        assertEquals(listOf(ServerEvent("ping", "{\"interval\": 30}")), events)
    }

    @Test
    fun `data lines of one event join with a newline`() {
        val events = parse("event: state", "data: {\"a\":", "data: 1}", "")

        assertEquals(listOf(ServerEvent("state", "{\"a\":\n1}")), events)
    }

    @Test
    fun `comments and unknown fields are skipped`() {
        val events = parse(": keep-alive", "retry: 100", "event: state", "data:x", "")

        assertEquals(listOf(ServerEvent("state", "x")), events)
    }

    @Test
    fun `an event carries its id and the next one starts without it`() {
        val events = parse("id: 7", "event: push", "data: a", "", "event: push", "data: b", "")

        assertEquals(listOf(ServerEvent("push", "a", "7"), ServerEvent("push", "b")), events)
    }

    @Test
    fun `a blank line with no data dispatches nothing and the next event starts clean`() {
        val events = parse("event: state", "", "data: y", "")

        assertEquals(listOf(ServerEvent("message", "y")), events)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class EventSourceTest {
    private class Stream {
        val connections = mutableListOf<ByteChannel>()
        val requests = mutableListOf<HttpRequestData>()
        var status = HttpStatusCode.OK

        fun open() = ByteChannel().also { connections += it }
    }

    private fun TestScope.client(stream: Stream): HttpClient =
        HttpClient(
            MockEngine(
                MockEngineConfig().apply {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler { request ->
                        stream.requests += request
                        val body = stream.connections[stream.requests.size - 1]
                        respond(body, stream.status, headersOf(HttpHeaders.ContentType, "text/event-stream"))
                    }
                },
            ),
        ) { applyJmapDefaults() }

    private fun TestScope.collect(
        stream: Stream,
        latch: AuthLatch = AuthLatch(),
        into: MutableList<StateChange>,
    ) = launch { stateChangeFlow(client(stream), latch, { TEMPLATE }, { "tok" }).collect(into::add) }

    @Test
    fun `a state event is emitted and a ping is not`() =
        runTest {
            val stream = Stream()
            val first = stream.open()
            val changes = mutableListOf<StateChange>()
            val job = collect(stream, into = changes)

            first.writeStringUtf8(PING + EMAIL_CHANGE + MAILBOX_CHANGE)
            first.flush()
            runCurrent()

            assertEquals(2, changes.size)
            assertTrue(changes[0].touches("Email"))
            assertTrue(changes[1].touches("Mailbox"))
            job.cancel()
        }

    @Test
    fun `the template is filled and the bearer token sent`() =
        runTest {
            val stream = Stream()
            stream.open()
            val job = collect(stream, into = mutableListOf())

            runCurrent()

            val request = stream.requests.single()
            assertEquals("Email,Mailbox,Thread,EmailDelivery", request.url.parameters["types"])
            assertEquals("no", request.url.parameters["closeafter"])
            assertEquals("30", request.url.parameters["ping"])
            assertEquals("Bearer tok", request.headers[HttpHeaders.Authorization])
            job.cancel()
        }

    @Test
    fun `a dropped stream reconnects and keeps emitting`() =
        runTest {
            val stream = Stream()
            val first = stream.open()
            val second = stream.open()
            val changes = mutableListOf<StateChange>()
            val job = collect(stream, into = changes)
            runCurrent()

            first.close()
            advanceTimeBy(2.seconds)
            second.writeStringUtf8(EMAIL_CHANGE)
            second.flush()
            runCurrent()

            assertEquals(2, stream.requests.size)
            assertEquals(1, changes.size)
            job.cancel()
        }

    @Test
    fun `a stream silent for twice the ping interval is dropped and reopened`() =
        runTest {
            val stream = Stream()
            val first = stream.open()
            val second = stream.open()
            val changes = mutableListOf<StateChange>()
            val job = collect(stream, into = changes)
            first.writeStringUtf8(PING)
            first.flush()
            runCurrent()

            advanceTimeBy(59.seconds)
            assertEquals(1, stream.requests.size)
            advanceTimeBy(3.seconds)
            second.writeStringUtf8(EMAIL_CHANGE)
            second.flush()
            runCurrent()

            assertEquals(2, stream.requests.size)
            assertEquals(1, changes.size)
            job.cancel()
        }

    @Test
    fun `a 401 trips the latch and ends the flow`() =
        runTest {
            val stream = Stream()
            stream.open()
            stream.status = HttpStatusCode.Unauthorized
            val latch = AuthLatch()

            val failure =
                assertFailsWith<JmapException> {
                    stateChangeFlow(client(stream), latch, { TEMPLATE }, { "tok" }).collect {}
                }

            assertEquals(JmapError.Unauthorized, failure.error)
            assertTrue(latch.tripped.value)
        }

    @Test
    fun `a server error is retried rather than ending the flow`() =
        runTest {
            val stream = Stream()
            stream.open()
            val second = stream.open()
            stream.status = HttpStatusCode.BadGateway
            val changes = mutableListOf<StateChange>()
            val job = collect(stream, into = changes)
            runCurrent()

            stream.status = HttpStatusCode.OK
            advanceTimeBy(2.seconds)
            second.writeStringUtf8(EMAIL_CHANGE)
            second.flush()
            runCurrent()

            assertEquals(1, changes.size)
            assertEquals(2, stream.requests.size)
            job.cancel()
        }
}
