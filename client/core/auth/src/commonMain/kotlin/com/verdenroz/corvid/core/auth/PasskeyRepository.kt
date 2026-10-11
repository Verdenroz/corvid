package com.verdenroz.corvid.core.auth

import com.verdenroz.corvid.core.jmap.JmapClient
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Instant

/** A passkey the server holds. [backupEligible] says the authenticator may sync it to other devices. */
data class Passkey(
    val id: String,
    val role: PasskeyRole,
    val backupEligible: Boolean,
    val createdAt: Instant,
)

sealed interface PasskeyListing {
    data class Listed(val passkeys: List<Passkey>) : PasskeyListing

    /** The server has passkey sign-in switched off. */
    data object NotServed : PasskeyListing
}

sealed interface CodeOutcome {
    /** The code is shown once and never stored by the server in the clear. */
    data class Minted(val code: String) : CodeOutcome

    /** The master token proved nothing, or is not the master's. */
    data object NotAccepted : CodeOutcome

    /** The server has passkey sign-in switched off. */
    data object NotServed : CodeOutcome
}

enum class RemoveOutcome {
    Removed,

    /** The server holds no such passkey. */
    Gone,

    /** It is the last passkey of its user, which the server keeps. */
    Last,
    NotAccepted,
}

private const val MASTER_HEADER = "X-Corvid-Master"
private const val AUTH_PATH = "/corvid/auth"
private val PASS_THROUGH = setOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden, HttpStatusCode.ServiceUnavailable)

/**
 * The passkey routes behind the web tier's session gate. Minting a code and removing a passkey need
 * a fresh master token, which rides one header on one request and is not kept.
 */
class PasskeyRepository(private val jmapClient: JmapClient) {
    suspend fun list(): PasskeyListing {
        val reply = jmapClient.webSend(HttpMethod.Get, "$AUTH_PATH/passkeys", passThrough = setOf(HttpStatusCode.ServiceUnavailable))
        if (reply.status == HttpStatusCode.ServiceUnavailable) return PasskeyListing.NotServed
        return PasskeyListing.Listed(
            reply.array.orEmpty().map { entry ->
                val fields = entry.jsonObject
                val role = fields.getValue("role").jsonPrimitive.content
                Passkey(
                    id = fields.getValue("id").jsonPrimitive.content,
                    role = PasskeyRole.entries.first { it.wire == role },
                    backupEligible = fields.getValue("backupEligible").jsonPrimitive.booleanOrNull == true,
                    createdAt = Instant.parse(fields.getValue("createdAt").jsonPrimitive.content),
                )
            },
        )
    }

    suspend fun mintCode(
        role: PasskeyRole,
        masterToken: String,
    ): CodeOutcome {
        val reply =
            jmapClient.webSend(
                HttpMethod.Post,
                "$AUTH_PATH/enroll-codes",
                buildJsonObject { put("role", role.wire) },
                headers = mapOf(MASTER_HEADER to masterToken),
                passThrough = PASS_THROUGH,
            )
        return when (reply.status) {
            HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> CodeOutcome.NotAccepted
            HttpStatusCode.ServiceUnavailable -> CodeOutcome.NotServed
            else -> CodeOutcome.Minted(reply.body.orEmpty().getValue("code").jsonPrimitive.content)
        }
    }

    suspend fun remove(
        id: String,
        masterToken: String,
    ): RemoveOutcome {
        val reply =
            jmapClient.webSend(
                HttpMethod.Delete,
                "$AUTH_PATH/passkeys/$id",
                headers = mapOf(MASTER_HEADER to masterToken),
                passThrough = PASS_THROUGH + HttpStatusCode.NotFound + HttpStatusCode.Conflict,
            )
        return when (reply.status) {
            HttpStatusCode.NotFound -> RemoveOutcome.Gone
            HttpStatusCode.Conflict -> RemoveOutcome.Last
            HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> RemoveOutcome.NotAccepted
            else -> RemoveOutcome.Removed
        }
    }
}
