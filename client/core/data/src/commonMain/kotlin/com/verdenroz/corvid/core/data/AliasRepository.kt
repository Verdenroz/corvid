package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.WebReply
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Instant

/**
 * A minted alias, `label.tag` at the mail domain. [missing] is one the server minted and no longer
 * holds, which a deploy wiped; it is never [enabled].
 */
data class Alias(
    val name: String,
    val address: String,
    val label: String,
    val enabled: Boolean,
    val createdAt: Instant?,
    val missing: Boolean,
)

sealed interface AliasListing {
    data class Listed(val aliases: List<Alias>) : AliasListing
}

sealed interface MintOutcome {
    data class Minted(val alias: Alias) : MintOutcome

    /** The label is not 1 to 20 of `a-z`, `0-9` and `-`. */
    data object BadLabel : MintOutcome
}

sealed interface SwitchOutcome {
    data class Switched(val alias: Alias) : SwitchOutcome

    /** The server holds no minted alias by that name. */
    data object Gone : SwitchOutcome
}

/** A mint, or an enable, past the server's cap on enabled, minted or hourly aliases. */
data object AtCap : MintOutcome, SwitchOutcome

/** What any alias route answers in place of its result. */
sealed interface AliasRefusal : AliasListing, MintOutcome, SwitchOutcome {
    /** The server has aliases switched off. */
    data object Off : AliasRefusal

    /** The mail server is rate-limiting the sentinel; a later try may land. */
    data object Busy : AliasRefusal

    /** The mail server refused or failed the change. */
    data object Failed : AliasRefusal

    /** The session, or on a switch the admin token, is not one the server accepts. */
    data object NotAccepted : AliasRefusal
}

private const val MASTER_HEADER = "X-Corvid-Master"
private const val ALIASES_PATH = "/corvid/aliases"
private val REFUSALS =
    setOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden, HttpStatusCode.ServiceUnavailable, HttpStatusCode.BadGateway)

/**
 * The alias routes behind the web tier's session gate. Disabling and enabling need a fresh admin
 * token, which rides one header on one request and is not kept.
 */
class AliasRepository(private val jmapClient: JmapClient) {
    suspend fun list(): AliasListing {
        val reply = jmapClient.webSend(HttpMethod.Get, ALIASES_PATH, passThrough = REFUSALS)
        return reply.refusal() ?: AliasListing.Listed(reply.array.orEmpty().map { it.jsonObject.toAlias() })
    }

    suspend fun mint(label: String): MintOutcome {
        val reply =
            jmapClient.webSend(
                HttpMethod.Post,
                ALIASES_PATH,
                buildJsonObject { put("label", label) },
                passThrough = REFUSALS + HttpStatusCode.BadRequest + HttpStatusCode.TooManyRequests,
            )
        return reply.refusal() ?: when (reply.status) {
            HttpStatusCode.BadRequest -> MintOutcome.BadLabel
            HttpStatusCode.TooManyRequests -> AtCap
            else -> MintOutcome.Minted(reply.body.orEmpty().toAlias())
        }
    }

    suspend fun disable(
        name: String,
        adminToken: String,
    ): SwitchOutcome = switch(name, "disable", adminToken)

    suspend fun enable(
        name: String,
        adminToken: String,
    ): SwitchOutcome = switch(name, "enable", adminToken)

    private suspend fun switch(
        name: String,
        action: String,
        adminToken: String,
    ): SwitchOutcome {
        val reply =
            jmapClient.webSend(
                HttpMethod.Post,
                "$ALIASES_PATH/$name/$action",
                headers = mapOf(MASTER_HEADER to adminToken),
                passThrough = REFUSALS + HttpStatusCode.NotFound + HttpStatusCode.TooManyRequests,
            )
        return reply.refusal() ?: when (reply.status) {
            HttpStatusCode.NotFound -> SwitchOutcome.Gone
            HttpStatusCode.TooManyRequests -> AtCap
            else -> SwitchOutcome.Switched(reply.body.orEmpty().toAlias())
        }
    }
}

// Both meanings of a 503 share the status: a switched-off router names itself, while a busy
// mail server leaves the body empty.
private fun WebReply.refusal(): AliasRefusal? =
    when (status) {
        HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> AliasRefusal.NotAccepted
        HttpStatusCode.ServiceUnavailable ->
            if (body?.get("error")?.jsonPrimitive?.contentOrNull == "aliases_off") AliasRefusal.Off else AliasRefusal.Busy
        HttpStatusCode.BadGateway -> AliasRefusal.Failed
        else -> null
    }

private fun Map<String, JsonElement>.toAlias() =
    Alias(
        name = getValue("name").jsonPrimitive.content,
        address = getValue("address").jsonPrimitive.content,
        label = getValue("label").jsonPrimitive.content,
        enabled = getValue("enabled").jsonPrimitive.booleanOrNull == true,
        createdAt = get("createdAt")?.jsonPrimitive?.contentOrNull?.let(Instant::parse),
        missing = getValue("missing").jsonPrimitive.booleanOrNull == true,
    )
