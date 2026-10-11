package com.verdenroz.corvid.core.auth

import com.verdenroz.corvid.core.model.ServerAddress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val CREATION =
    """{"challenge":"Y2hhbGxlbmdl","rp":{"id":"rig.test","name":"Corvid"},""" +
        """"user":{"id":"aGFuZGxl","name":"mailuser","displayName":"Mail User"},""" +
        """"pubKeyCredParams":[{"type":"public-key","alg":-7}],""" +
        """"authenticatorSelection":{"residentKey":"preferred","userVerification":"required"},""" +
        """"attestation":"none","timeout":300000,"excludeCredentials":[{"type":"public-key","id":"AQID"}]}"""

private const val REQUEST =
    """{"challenge":"Y2hhbGxlbmdl","rpId":"rig.test","userVerification":"required","timeout":300000,""" +
        """"allowCredentials":[{"type":"public-key","id":"AQID"},{"type":"public-key","id":"BAUG"}]}"""

/** A CBOR map of fmt none, an empty attStmt and authData, up to the length of the byte string. */
private const val ATTESTATION_PREFIX = "a363666d74646e6f6e656761747453746d74a068617574684461746" + "1"

private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

class CtapTest {
    @Test
    fun `client data is the compact JSON a browser would write`() {
        assertEquals(
            """{"type":"webauthn.get","challenge":"Y2g","origin":"https://mail.rig.test:4443","crossOrigin":false}""",
            clientDataJson("webauthn.get", "Y2g", "https://mail.rig.test:4443"),
        )
    }

    @Test
    fun `the origin is the dialled address with its port written out`() {
        assertEquals("https://mail.rig.test:4443", originOf(ServerAddress("https://mail.rig.test:4443")))
        assertEquals("https://mail.rig.test:443", originOf(ServerAddress("https://mail.rig.test")))
        assertEquals("https://mail.rig.test:4443", originOf(ServerAddress("https://mail.rig.test:4443/")))
    }

    @Test
    fun `base64url has no padding and no plus or slash`() {
        val bytes = byteArrayOf(-5, -1, -2, 0, 1)

        val text = encodeBase64Url(bytes)

        assertEquals("-__-AAE", text)
        assertContentEquals(bytes, decodeBase64Url(text))
    }

    @Test
    fun `an attestation object around short authenticator data uses the one byte header`() {
        val data = ByteArray(10) { it.toByte() }

        assertEquals("a363666d74646e6f6e656761747453746d74a0686175746844617461" + "4a" + hex(data), hex(attestationObject(data)))
    }

    @Test
    fun `an attestation object around mid length authenticator data uses the two byte header`() {
        val data = ByteArray(100) { it.toByte() }

        assertEquals("a363666d74646e6f6e656761747453746d74a0686175746844617461" + "5864" + hex(data), hex(attestationObject(data)))
    }

    @Test
    fun `an attestation object around long authenticator data uses the three byte header`() {
        val data = ByteArray(300) { it.toByte() }

        assertEquals("a363666d74646e6f6e656761747453746d74a0686175746844617461" + "59012c" + hex(data), hex(attestationObject(data)))
        assertTrue(ATTESTATION_PREFIX.isNotEmpty())
    }

    @Test
    fun `creation options are read as the sentinel writes them`() {
        val options = creationOptions(CREATION)

        assertEquals("Y2hhbGxlbmdl", options.challenge)
        assertEquals("rig.test", options.rpId)
        assertEquals("Corvid", options.rpName)
        assertContentEquals("handle".toByteArray(), options.userId)
        assertEquals("mailuser", options.userName)
        assertEquals("Mail User", options.displayName)
        assertContentEquals(byteArrayOf(1, 2, 3), options.exclude.single())
        assertEquals(false, options.discoverable)
    }

    @Test
    fun `a required resident key is asked for`() {
        assertEquals(true, creationOptions(CREATION.replace("preferred", "required")).discoverable)
    }

    @Test
    fun `options that allow no ES256 credential are refused`() {
        assertFailsWith<IllegalArgumentException> { creationOptions(CREATION.replace("-7", "-257")) }
    }

    @Test
    fun `request options carry every allowed credential`() {
        val options = requestOptions(REQUEST)

        assertEquals("rig.test", options.rpId)
        assertEquals(listOf(listOf<Byte>(1, 2, 3), listOf<Byte>(4, 5, 6)), options.allow.map { it.toList() })
    }

    @Test
    fun `a registration response carries the ids, the client data and the none attestation`() {
        val made = Made(byteArrayOf(9, 9), ByteArray(40) { 7 })

        val response = Json.parseToJsonElement(registrationResponse("""{"a":1}""", made)).jsonObject

        assertEquals("CQk", response.getValue("id").jsonPrimitive.content)
        assertEquals("CQk", response.getValue("rawId").jsonPrimitive.content)
        assertEquals("public-key", response.getValue("type").jsonPrimitive.content)
        val inner = response.getValue("response").jsonObject
        assertEquals("eyJhIjoxfQ", inner.getValue("clientDataJSON").jsonPrimitive.content)
        assertContentEquals(
            attestationObject(made.authenticatorData),
            decodeBase64Url(inner.getValue("attestationObject").jsonPrimitive.content),
        )
    }

    @Test
    fun `an authentication response carries the signature and a user handle only when the key gave one`() {
        val without = Asserted(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3), null)
        val with = Asserted(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3), byteArrayOf(4))

        val first = Json.parseToJsonElement(authenticationResponse("{}", without)).jsonObject.getValue("response").jsonObject
        val second = Json.parseToJsonElement(authenticationResponse("{}", with)).jsonObject.getValue("response").jsonObject

        assertEquals("Ag", first.getValue("authenticatorData").jsonPrimitive.content)
        assertEquals("Aw", first.getValue("signature").jsonPrimitive.content)
        assertNull(first["userHandle"])
        assertEquals("BA", second.getValue("userHandle").jsonPrimitive.content)
    }

    @Test
    fun `libfido2 error numbers map to what the person can do about them`() {
        val expected =
            mapOf(
                0x31 to SecurityKeyError.PinInvalid,
                0x35 to SecurityKeyError.PinNotSet,
                0x36 to SecurityKeyError.PinNotSet,
                0x32 to SecurityKeyError.PinBlocked,
                0x34 to SecurityKeyError.PinAuthBlocked,
                0x2e to SecurityKeyError.NoCredentials,
                0x19 to SecurityKeyError.CredentialExcluded,
                0x2f to SecurityKeyError.NotTouched,
                0x3a to SecurityKeyError.NotTouched,
                0x2d to SecurityKeyError.Cancelled,
                0x26 to SecurityKeyError.Unsupported,
                -2 to SecurityKeyError.Other,
            )
        expected.forEach { (code, error) -> assertEquals(error, securityKeyError(code), "0x${code.toString(16)}") }
    }
}
