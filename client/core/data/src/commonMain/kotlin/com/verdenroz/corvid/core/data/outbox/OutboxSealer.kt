package com.verdenroz.corvid.core.data.outbox

import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.hybrid.HpkeParameters
import com.google.crypto.tink.hybrid.HpkePrivateKey
import com.google.crypto.tink.hybrid.HpkePublicKey
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import com.google.crypto.tink.util.Bytes
import com.google.crypto.tink.util.SecretBytes
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.Base64

private const val KEY_BYTES = 32
private val DERIVATION_INFO = "corvid-outbox-seal-v1".toByteArray()

/** The public half of the key a device seals its outbox to; the private half is derived again on every unlock. */
class SealKey(val publicKey: ByteArray) {
    val fingerprint: String = MessageDigest.getInstance("SHA-256").digest(publicKey).take(8).joinToString("") { "%02x".format(it) }

    fun encoded(): String = Base64.getEncoder().encodeToString(publicKey)

    companion object {
        fun decode(encoded: String) = SealKey(Base64.getDecoder().decode(encoded))
    }
}

sealed interface OpenResult {
    class Opened(val message: OutboxMessage) : OpenResult

    /** Sealed to a key other than the one this device key derives. */
    class KeyMismatch(val sealedTo: String) : OpenResult

    data object Corrupt : OpenResult
}

/** HPKE (X25519, HKDF-SHA256, AES-256-GCM) to a keypair derived from the device key. */
object OutboxSealer {
    init {
        HybridConfig.register()
    }

    fun keyFor(deviceKey: ByteArray): SealKey = SealKey(X25519.publicFromPrivate(privateKeyFor(deviceKey)))

    /** The id is bound into the ciphertext, so a sealed file cannot be passed off under another id. */
    fun seal(
        id: String,
        message: OutboxMessage,
        to: SealKey,
    ): SealedMessage {
        val publicKey = HpkePublicKey.create(PARAMETERS, Bytes.copyFrom(to.publicKey), null)
        val handle = KeysetHandle.newBuilder().addEntry(KeysetHandle.importKey(publicKey).withRandomId().makePrimary()).build()
        val primitive = handle.getPrimitive(RegistryConfiguration.get(), HybridEncrypt::class.java)
        return SealedMessage(to.fingerprint, primitive.encrypt(message.encode(), id.toByteArray()))
    }

    fun open(
        id: String,
        sealed: SealedMessage,
        deviceKey: ByteArray,
    ): OpenResult {
        val privateBytes = privateKeyFor(deviceKey)
        val key = SealKey(X25519.publicFromPrivate(privateBytes))
        if (key.fingerprint != sealed.keyFingerprint) return OpenResult.KeyMismatch(sealed.keyFingerprint)
        val publicKey = HpkePublicKey.create(PARAMETERS, Bytes.copyFrom(key.publicKey), null)
        val privateKey = HpkePrivateKey.create(publicKey, SecretBytes.copyFrom(privateBytes, InsecureSecretKeyAccess.get()))
        val handle = KeysetHandle.newBuilder().addEntry(KeysetHandle.importKey(privateKey).withRandomId().makePrimary()).build()
        val primitive = handle.getPrimitive(RegistryConfiguration.get(), HybridDecrypt::class.java)
        return try {
            OpenResult.Opened(decodeOutboxMessage(primitive.decrypt(sealed.bytes, id.toByteArray())))
        } catch (_: GeneralSecurityException) {
            OpenResult.Corrupt
        }
    }

    private val PARAMETERS: HpkeParameters =
        HpkeParameters.builder()
            .setVariant(HpkeParameters.Variant.NO_PREFIX)
            .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
            .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
            .setAeadId(HpkeParameters.AeadId.AES_256_GCM)
            .build()

    private fun privateKeyFor(deviceKey: ByteArray): ByteArray =
        Hkdf.computeHkdf("HmacSha256", deviceKey, ByteArray(0), DERIVATION_INFO, KEY_BYTES)
}
