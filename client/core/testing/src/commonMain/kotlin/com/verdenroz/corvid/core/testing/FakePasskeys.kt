package com.verdenroz.corvid.core.testing

import com.verdenroz.corvid.core.jmap.JmapJson
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One passkey the fake sentinel holds; [role] is the wire spelling. */
data class FakePasskey(
    val id: String,
    val role: String,
    val backupEligible: Boolean = false,
    val createdAt: String = "2030-01-01T00:00:00Z",
)

/** `/corvid/auth` behind the session gate: the passkey list, and the removals and codes only a master token allows. */
class FakePasskeys {
    /** The only `X-Corvid-Master` value a removal or a code accepts. */
    var masterToken: String = "master-token"

    /** Answers every route 503 `auth_off`, as a sentinel without `sentinel.auth` does. */
    var off: Boolean = false

    /** What a minted code reads as. */
    var nextCode: String = "ABCD-EFGH-IJKL-MNOP"

    val passkeys: MutableList<FakePasskey> = mutableListOf()

    /** The role each minted code was for, in order. */
    val minted: MutableList<String> = mutableListOf()

    /** The master header each removal or code carried, null where there was none. */
    val masters: MutableList<String?> = mutableListOf()

    internal fun handle(
        scope: MockRequestHandleScope,
        request: HttpRequestData,
    ): HttpResponseData {
        if (off) return json(scope, HttpStatusCode.ServiceUnavailable, buildJsonObject { put("error", "auth_off") })
        val segments = request.url.encodedPath.removePrefix("/corvid/auth").trim('/').split("/")
        return when {
            segments == listOf("passkeys") && request.method == HttpMethod.Get -> list(scope)
            segments.size == 2 && segments[0] == "passkeys" && request.method == HttpMethod.Delete -> remove(scope, request, segments[1])
            segments == listOf("enroll-codes") && request.method == HttpMethod.Post -> mint(scope, request)
            else -> scope.respond("not found", HttpStatusCode.NotFound)
        }
    }

    private fun list(scope: MockRequestHandleScope): HttpResponseData =
        scope.respond(
            JsonArray(
                passkeys.map {
                    buildJsonObject {
                        put("id", it.id)
                        put("role", it.role)
                        put("backupEligible", it.backupEligible)
                        put("createdAt", it.createdAt)
                    }
                },
            ).toString(),
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, listOf("application/json")),
        )

    private fun remove(
        scope: MockRequestHandleScope,
        request: HttpRequestData,
        id: String,
    ): HttpResponseData {
        masters += request.headers["X-Corvid-Master"]
        if (request.headers["X-Corvid-Master"] != masterToken) return scope.respond("", HttpStatusCode.Unauthorized)
        val found = passkeys.firstOrNull { it.id == id } ?: return scope.respond("", HttpStatusCode.NotFound)
        if (passkeys.count { it.role == found.role } == 1) return scope.respond("", HttpStatusCode.Conflict)
        passkeys.remove(found)
        return scope.respond("", HttpStatusCode.NoContent)
    }

    private fun mint(
        scope: MockRequestHandleScope,
        request: HttpRequestData,
    ): HttpResponseData {
        masters += request.headers["X-Corvid-Master"]
        if (request.headers["X-Corvid-Master"] != masterToken) return scope.respond("", HttpStatusCode.Unauthorized)
        val body = JmapJson.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()) as JsonObject
        minted += body.getValue("role").jsonPrimitive.content
        return json(
            scope,
            HttpStatusCode.OK,
            buildJsonObject {
                put("code", nextCode)
                put("expiresAt", "2030-01-02T00:00:00Z")
            },
        )
    }

    private fun json(
        scope: MockRequestHandleScope,
        status: HttpStatusCode,
        body: JsonObject,
    ): HttpResponseData = scope.respond(body.toString(), status, headersOf(HttpHeaders.ContentType, listOf("application/json")))
}
