package com.verdenroz.corvid.core.testing

import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One minted alias the fake sentinel lists; [missing] is a ledger entry Stalwart no longer holds. */
data class FakeAlias(
    val name: String,
    val enabled: Boolean = true,
    val createdAt: String? = "2030-01-01T00:00:00Z",
    val missing: Boolean = false,
)

private val LABEL = Regex("[a-z0-9-]{1,20}")
private val SWITCHES = setOf("disable", "enable")

/** `/corvid/aliases` behind the session gate: the list and mints any session allows, and the switches only an admin token allows. */
class FakeAliases {
    /** The only `X-Corvid-Master` value a switch accepts. */
    var masterToken: String = "master-token"

    var domain: String = "corvid.test"

    /** Answers every route an empty 401 with no `WWW-Authenticate`, as the sentinel's daily-session check does. */
    var dailyRefused: Boolean = false

    /** Answers every route the mail server's 404, as a web tier without the alias routes does. */
    var unrouted: Boolean = false

    /** Answers every route 503 `aliases_off`, as a sentinel without the alias routes does. */
    var off: Boolean = false

    /** Answers every route an empty 503, as the sentinel does once Stalwart has answered 429 twice. */
    var busy: Boolean = false

    /** Answers every route 502, as the sentinel does when Stalwart refused or failed. */
    var failing: Boolean = false

    /** Refuses a mint, or enabling a disabled alias, with 429, as a reached cap does. */
    var atCap: Boolean = false

    /** The tag a minted alias gets after its label. */
    var nextTag: String = "abcd2345"

    val aliases: MutableList<FakeAlias> = mutableListOf()

    /** Every label body a mint carried, as sent. */
    val labels: MutableList<String> = mutableListOf()

    /** The admin header each switch carried, null where there was none. */
    val masters: MutableList<String?> = mutableListOf()

    internal fun handle(
        scope: MockRequestHandleScope,
        request: HttpRequestData,
    ): HttpResponseData {
        if (unrouted) return scope.respond("not found", HttpStatusCode.NotFound)
        if (dailyRefused) return scope.respond("", HttpStatusCode.Unauthorized)
        if (off) return json(scope, HttpStatusCode.ServiceUnavailable, buildJsonObject { put("error", "aliases_off") })
        if (busy) return scope.respond("", HttpStatusCode.ServiceUnavailable)
        if (failing) return scope.respond("", HttpStatusCode.BadGateway)
        val segments = request.url.encodedPath.removePrefix("/corvid/aliases").trim('/').split("/")
        return when {
            segments == listOf("") && request.method == HttpMethod.Get -> list(scope)
            segments == listOf("") && request.method == HttpMethod.Post -> mint(scope, request)
            segments.size == 2 && segments[1] in SWITCHES && request.method == HttpMethod.Post ->
                switch(scope, request, segments[0], segments[1] == "enable")
            else -> scope.respond("not found", HttpStatusCode.NotFound)
        }
    }

    private fun list(scope: MockRequestHandleScope): HttpResponseData =
        scope.respond(
            JsonArray(aliases.map { it.toJson() }).toString(),
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, listOf("application/json")),
        )

    private fun mint(
        scope: MockRequestHandleScope,
        request: HttpRequestData,
    ): HttpResponseData {
        val label = body(request).getValue("label").jsonPrimitive.content
        labels += label
        if (!LABEL.matches(label.lowercase())) return scope.respond("", HttpStatusCode.BadRequest)
        if (atCap) return scope.respond("", HttpStatusCode.TooManyRequests)
        val alias = FakeAlias("${label.lowercase()}.$nextTag", createdAt = "2030-01-02T00:00:00Z")
        aliases += alias
        return json(scope, HttpStatusCode.Created, alias.toJson())
    }

    private fun switch(
        scope: MockRequestHandleScope,
        request: HttpRequestData,
        name: String,
        enabled: Boolean,
    ): HttpResponseData {
        val index = aliases.indexOfFirst { it.name == name && !it.missing }
        if ('.' !in name || index < 0) return scope.respond("", HttpStatusCode.NotFound)
        if (enabled && !aliases[index].enabled && atCap) return scope.respond("", HttpStatusCode.TooManyRequests)
        masters += request.headers["X-Corvid-Master"]
        if (request.headers["X-Corvid-Master"] != masterToken) return scope.respond("", HttpStatusCode.Unauthorized)
        aliases[index] = aliases[index].copy(enabled = enabled)
        return json(scope, HttpStatusCode.OK, aliases[index].toJson())
    }

    private fun FakeAlias.toJson(): JsonObject =
        buildJsonObject {
            put("name", name)
            put("address", "$name@$domain")
            put("label", name.substringBeforeLast('.'))
            put("enabled", enabled)
            put("createdAt", createdAt)
            put("missing", missing)
        }
}
