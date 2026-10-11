package com.verdenroz.corvid.core.auth

import com.verdenroz.corvid.core.jmap.JmapError
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.model.ServerAddress
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import javax.net.ssl.SSLException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The two identities a passkey can sign in as. */
enum class PasskeyRole(val wire: String) {
    Daily("daily"),
    Master("master"),
}

/** A sign-in's tokens. The master role gets an access token and no [refreshToken]. */
data class AuthTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: Instant,
)

sealed interface Refreshed {
    data class Tokens(val tokens: AuthTokens) : Refreshed

    /** The refresh token is spent, expired or revoked: only a passkey signs in again. */
    data object InvalidGrant : Refreshed
}

/** What the sentinel said about a ceremony, as opposed to the network failing, which is a [JmapException]. */
sealed class AuthFailure : Exception() {
    /** The enrollment code is unknown, spent or expired. */
    data object BadCode : AuthFailure()

    /** The passkey was not accepted. */
    data object Refused : AuthFailure()

    /** No passkey is enrolled for the role. */
    data object NotEnrolled : AuthFailure()

    /** The server has passkey sign-in switched off. */
    data object Off : AuthFailure()

    /** The code was redeemed with a passkey the server already holds. */
    data object AlreadyEnrolled : AuthFailure()
}

/** The sentinel's public passkey routes: everything a client needs before it holds a token. */
interface AuthApi {
    /** `PublicKeyCredentialCreationOptionsJSON` for the role the [code] enrolls. */
    suspend fun registerOptions(
        server: ServerAddress,
        code: String,
    ): String

    suspend fun registerFinish(
        server: ServerAddress,
        credential: String,
    ): PasskeyRole

    suspend fun loginOptions(
        server: ServerAddress,
        role: PasskeyRole,
    ): String

    suspend fun loginFinish(
        server: ServerAddress,
        credential: String,
    ): AuthTokens

    suspend fun refresh(
        server: ServerAddress,
        refreshToken: String,
    ): Refreshed
}

private const val AUTH_PATH = "/corvid/auth"
private val json = Json { ignoreUnknownKeys = true }

private fun parseOrNull(body: String): JsonObject? =
    try {
        json.parseToJsonElement(body).jsonObject
    } catch (e: IllegalArgumentException) {
        null
    }

class HttpAuthApi(
    private val httpClient: HttpClient,
    private val clock: Clock = Clock.System,
) : AuthApi {
    override suspend fun registerOptions(
        server: ServerAddress,
        code: String,
    ): String =
        post(server, "register/options", buildJsonObject { put("code", code) }.toString())
            .ok(HttpStatusCode.Unauthorized to AuthFailure.BadCode)

    override suspend fun registerFinish(
        server: ServerAddress,
        credential: String,
    ): PasskeyRole {
        val body =
            post(server, "register/finish", credential)
                .ok(HttpStatusCode.Unauthorized to AuthFailure.BadCode, HttpStatusCode.Conflict to AuthFailure.AlreadyEnrolled)
        val role = parse(body).getValue("role").jsonPrimitive.content
        return PasskeyRole.entries.first { it.wire == role }
    }

    override suspend fun loginOptions(
        server: ServerAddress,
        role: PasskeyRole,
    ): String =
        post(server, "login/options", buildJsonObject { put("role", role.wire) }.toString())
            .ok(HttpStatusCode.NotFound to AuthFailure.NotEnrolled)

    override suspend fun loginFinish(
        server: ServerAddress,
        credential: String,
    ): AuthTokens = tokens(post(server, "login/finish", credential).ok(HttpStatusCode.Unauthorized to AuthFailure.Refused))

    override suspend fun refresh(
        server: ServerAddress,
        refreshToken: String,
    ): Refreshed {
        val reply = post(server, "token", buildJsonObject { put("refresh_token", refreshToken) }.toString())
        if (reply.status == HttpStatusCode.ServiceUnavailable) throw JmapException(JmapError.Server(reply.status.value))
        if (reply.status == HttpStatusCode.BadRequest && reply.error() == "invalid_grant") return Refreshed.InvalidGrant
        return Refreshed.Tokens(tokens(reply.ok()))
    }

    private fun tokens(body: String): AuthTokens {
        val fields = parse(body)
        return AuthTokens(
            accessToken = fields.getValue("access_token").jsonPrimitive.content,
            refreshToken = fields["refresh_token"]?.jsonPrimitive?.contentOrNull,
            expiresAt = clock.now() + fields.getValue("expires_in").jsonPrimitive.content.toLong().seconds,
        )
    }

    private fun parse(body: String): JsonObject =
        parseOrNull(body) ?: throw JmapException(JmapError.Request("malformed", "the server's answer was not a JSON object"))

    private suspend fun post(
        server: ServerAddress,
        path: String,
        body: String,
    ): Reply =
        try {
            val response: HttpResponse =
                httpClient.post("${server.baseUrl}$AUTH_PATH/$path") { setBody(TextContent(body, ContentType.Application.Json)) }
            Reply(response.status, response.bodyAsText(), response.headers[HttpHeaders.RetryAfter]?.toLongOrNull())
        } catch (e: SSLException) {
            throw JmapException(JmapError.TlsFailure(e))
        } catch (e: IOException) {
            throw JmapException(JmapError.Unreachable(e))
        }

    private class Reply(val status: HttpStatusCode, val text: String, val retryAfter: Long?) {
        fun error(): String? = parseOrNull(text)?.get("error")?.jsonPrimitive?.contentOrNull

        /** The body of a 200, else the failure [known] names for the status, else the transport's own. */
        fun ok(vararg known: Pair<HttpStatusCode, AuthFailure>): String {
            if (status == HttpStatusCode.OK) return text
            known.firstOrNull { it.first == status }?.let { throw it.second }
            throw when {
                status == HttpStatusCode.ServiceUnavailable -> AuthFailure.Off
                status == HttpStatusCode.TooManyRequests -> JmapException(JmapError.RateLimited(retryAfter))
                status.value >= 500 -> JmapException(JmapError.Server(status.value))
                else -> JmapException(JmapError.Request("unexpected", "status ${status.value}"))
            }
        }
    }
}
