package com.verdenroz.corvid.core.auth

import com.verdenroz.corvid.core.model.ServerAddress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private val json = Json { ignoreUnknownKeys = true }

@OptIn(ExperimentalEncodingApi::class)
private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

@OptIn(ExperimentalEncodingApi::class)
internal fun encodeBase64Url(bytes: ByteArray): String = base64Url.encode(bytes)

@OptIn(ExperimentalEncodingApi::class)
internal fun decodeBase64Url(text: String): ByteArray = base64Url.decode(text)

/** The standard serialized origin of the web tier: the dialled address, with no trailing slash. */
internal fun originOf(server: ServerAddress): String = server.baseUrl.trimEnd('/')

internal fun clientDataJson(
    type: String,
    challenge: String,
    origin: String,
): String =
    buildJsonObject {
        put("type", type)
        put("challenge", challenge)
        put("origin", origin)
        put("crossOrigin", false)
    }.toString()

internal fun sha256(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())

/**
 * A `none` attestation object around the key's authenticator data: the key's own attestation is
 * dropped, since the sentinel takes the credential on trust and accepts only this format.
 */
internal fun attestationObject(authenticatorData: ByteArray): ByteArray =
    byteArrayOf(0xa3.toByte(), 0x63) + "fmt".toByteArray() +
        byteArrayOf(0x64) + "none".toByteArray() +
        byteArrayOf(0x67) + "attStmt".toByteArray() + byteArrayOf(0xa0.toByte()) +
        byteArrayOf(0x68) + "authData".toByteArray() +
        byteStringHeader(authenticatorData.size) + authenticatorData

/** The CBOR header of a byte string of [length] bytes, for the lengths a key's authenticator data takes. */
internal fun byteStringHeader(length: Int): ByteArray =
    when {
        length < 24 -> byteArrayOf((0x40 + length).toByte())
        length < 0x100 -> byteArrayOf(0x58, length.toByte())
        else -> byteArrayOf(0x59, (length shr 8).toByte(), length.toByte())
    }

/** The bytes inside a CBOR byte string, which is how libfido2 older than 1.14 hands out an assertion's authenticator data. */
internal fun unwrapByteString(cbor: ByteArray): ByteArray {
    val header =
        when (cbor[0].toInt() and 0xff) {
            0x58 -> 2
            0x59 -> 3
            else -> 1
        }
    return cbor.copyOfRange(header, cbor.size)
}

/** The challenge and what the key is asked, from `PublicKeyCredentialCreationOptionsJSON`. */
internal fun creationOptions(options: String): Pair<String, Creation> {
    val fields = json.parseToJsonElement(options).jsonObject
    val rp = fields.getValue("rp").jsonObject
    val user = fields.getValue("user").jsonObject
    require(
        fields.getValue("pubKeyCredParams").jsonArray.any { it.jsonObject.getValue("alg").jsonPrimitive.content == "-7" },
    ) { "the server does not accept ES256" }
    return fields.getValue("challenge").jsonPrimitive.content to
        Creation(
            rpId = rp.getValue("id").jsonPrimitive.content,
            rpName = rp["name"]?.jsonPrimitive?.contentOrNull ?: rp.getValue("id").jsonPrimitive.content,
            userId = decodeBase64Url(user.getValue("id").jsonPrimitive.content),
            userName = user.getValue("name").jsonPrimitive.content,
            displayName = user["displayName"]?.jsonPrimitive?.contentOrNull ?: user.getValue("name").jsonPrimitive.content,
            exclude = credentialIds(fields["excludeCredentials"]),
            discoverable = fields["authenticatorSelection"]?.jsonObject?.get("residentKey")?.jsonPrimitive?.contentOrNull == "required",
        )
}

/** The challenge and what the key is asked, from `PublicKeyCredentialRequestOptionsJSON`. */
internal fun requestOptions(options: String): Pair<String, Request> {
    val fields = json.parseToJsonElement(options).jsonObject
    return fields.getValue("challenge").jsonPrimitive.content to
        Request(
            rpId = fields.getValue("rpId").jsonPrimitive.content,
            allow = credentialIds(fields["allowCredentials"]),
        )
}

private fun credentialIds(list: JsonElement?): List<ByteArray> =
    (list as? JsonArray).orEmpty().map { decodeBase64Url(it.jsonObject.getValue("id").jsonPrimitive.content) }

internal fun registrationResponse(
    clientDataJson: String,
    made: Made,
): String =
    buildJsonObject {
        val id = encodeBase64Url(made.credentialId)
        put("id", id)
        put("rawId", id)
        put("type", "public-key")
        putJsonObject("response") {
            put("clientDataJSON", encodeBase64Url(clientDataJson.toByteArray()))
            put("attestationObject", encodeBase64Url(attestationObject(made.authenticatorData)))
            put("transports", buildJsonArray { add(JsonPrimitive("usb")) })
        }
        putJsonObject("clientExtensionResults") {}
    }.toString()

internal fun authenticationResponse(
    clientDataJson: String,
    asserted: Asserted,
): String =
    buildJsonObject {
        val id = encodeBase64Url(asserted.credentialId)
        put("id", id)
        put("rawId", id)
        put("type", "public-key")
        putJsonObject("response") {
            put("clientDataJSON", encodeBase64Url(clientDataJson.toByteArray()))
            put("authenticatorData", encodeBase64Url(asserted.authenticatorData))
            put("signature", encodeBase64Url(asserted.signature))
            asserted.userHandle?.let { put("userHandle", encodeBase64Url(it)) }
        }
        putJsonObject("clientExtensionResults") {}
    }.toString()
