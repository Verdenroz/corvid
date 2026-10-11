package com.verdenroz.corvid

import com.google.crypto.tink.apps.fixed_webpush.WebPushHybridDecrypt
import com.google.crypto.tink.subtle.EllipticCurves
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

private const val AUTH_BYTES = 16
private val base64Url = Base64.getUrlEncoder().withoutPadding()

/** The keys an own connection's pushes are encrypted to (RFC 8291): a P-256 pair and a 16-byte auth secret. */
internal class StreamKeys(
    private val privateKey: ECPrivateKey,
    private val publicKey: ECPublicKey,
    private val auth: ByteArray,
) {
    /** What the server needs to encrypt to these keys, base64url: the public point, uncompressed, and the secret. */
    val p256dh: String =
        base64Url.encodeToString(
            EllipticCurves.pointEncode(EllipticCurves.CurveType.NIST_P256, EllipticCurves.PointFormatType.UNCOMPRESSED, publicKey.w),
        )
    val authSecret: String = base64Url.encodeToString(auth)

    /** Throws `GeneralSecurityException` for a body these keys did not decrypt. */
    fun decrypt(sealed: ByteArray): ByteArray =
        WebPushHybridDecrypt.Builder()
            .withAuthSecret(auth)
            .withRecipientPublicKey(publicKey)
            .withRecipientPrivateKey(privateKey)
            .build()
            .decrypt(sealed, null)

    /** PKCS#8, X.509 and the secret, for [StreamKeyStore] to keep. */
    internal fun encoded(): Triple<ByteArray, ByteArray, ByteArray> = Triple(privateKey.encoded, publicKey.encoded, auth)

    companion object {
        fun generate(): StreamKeys {
            val pair =
                KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            return StreamKeys(
                pair.private as ECPrivateKey,
                pair.public as ECPublicKey,
                ByteArray(AUTH_BYTES).also(SecureRandom()::nextBytes),
            )
        }

        fun decode(
            private: ByteArray,
            public: ByteArray,
            auth: ByteArray,
        ): StreamKeys {
            val factory = KeyFactory.getInstance("EC")
            return StreamKeys(
                factory.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(private)) as ECPrivateKey,
                factory.generatePublic(java.security.spec.X509EncodedKeySpec(public)) as ECPublicKey,
                auth,
            )
        }
    }
}
