package com.verdenroz.corvid

import com.google.crypto.tink.subtle.EllipticCurves
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private fun b64(text: String): ByteArray = Base64.getUrlDecoder().decode(text)

/** The receiver of RFC 8291 appendix A. */
private fun rfcKeys(): StreamKeys {
    val params =
        AlgorithmParameters.getInstance("EC")
            .apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
    val factory = KeyFactory.getInstance("EC")
    val scalar = BigInteger(1, b64("q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94"))
    val private = factory.generatePrivate(ECPrivateKeySpec(scalar, params))
    val point =
        EllipticCurves.pointDecode(
            EllipticCurves.CurveType.NIST_P256,
            EllipticCurves.PointFormatType.UNCOMPRESSED,
            b64("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"),
        )
    val public = factory.generatePublic(ECPublicKeySpec(point, params))
    return StreamKeys(private as ECPrivateKey, public as ECPublicKey, b64("BTBZMqHH6r4Tts7J_aSIgg"))
}

private const val RFC_BODY =
    "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6" +
        "PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN"

class StreamKeysTest {
    @Test
    fun `the RFC 8291 example decrypts to its plaintext`() {
        assertEquals("When I grow up, I want to be a watermelon", rfcKeys().decrypt(b64(RFC_BODY)).decodeToString())
    }

    @Test
    fun `the keys publish the point and secret the server encrypts to`() {
        val keys = rfcKeys()

        assertEquals("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4", keys.p256dh)
        assertEquals("BTBZMqHH6r4Tts7J_aSIgg", keys.authSecret)
    }

    @Test
    fun `kept keys decrypt after they are restored`() {
        val (private, public, auth) = rfcKeys().encoded()

        val restored = StreamKeys.decode(private, public, auth)

        assertEquals("When I grow up, I want to be a watermelon", restored.decrypt(b64(RFC_BODY)).decodeToString())
    }

    @Test
    fun `a body another device's keys sealed does not decrypt`() {
        assertFailsWith<GeneralSecurityException> { StreamKeys.generate().decrypt(b64(RFC_BODY)) }
    }
}
