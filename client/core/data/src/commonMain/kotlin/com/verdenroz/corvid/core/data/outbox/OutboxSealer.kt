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
import com.verdenroz.corvid.core.common.sha256Hex
import kotlinx.serialization.SerializationException
import java.security.GeneralSecurityException
import java.util.Base64

private const val KEY_BYTES = 32
private val DERIVATION_INFO = "corvid-outbox-seal-v1".toByteArray()

/** The public half of the key a device seals its outbox to; the private half is derived again on every unlock. */
class SealKey(val publicKey: ByteArray) {
    val fingerprint: String = sha256Hex(publicKey).take(16)

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

    /**
     * New mail is always sealed to [keyFor] the current device key, since the private half of any other is
     * unavailable. This says whether the [stored] key, which seals mail queued while locked, should be
     * replaced by it: not while unsent mail is still sealed to the stored one.
     */
    fun shouldStoreKey(
        deviceKey: ByteArray,
        stored: String?,
        store: OutboxStore,
    ): Boolean {
        val held = stored?.let(SealKey::decode)
        if (held?.fingerprint == keyFor(deviceKey).fingerprint) return false
        return held == null || store.list().items.none { it.keyFingerprint == held.fingerprint && it.state !is OutboxState.Sent }
    }

    fun keyFor(deviceKey: ByteArray): SealKey = SealKey(X25519.publicFromPrivate(privateKeyFor(deviceKey)))

    fun seal(
        id: String,
        message: OutboxMessage,
        to: SealKey,
    ): SealedMessage = sealPayload(id, message.encode(), to)

    /** The id is bound into the ciphertext, so a sealed file cannot be passed off under another id. */
    internal fun sealPayload(
        id: String,
        payload: ByteArray,
        to: SealKey,
    ): SealedMessage {
        val publicKey = HpkePublicKey.create(PARAMETERS, Bytes.copyFrom(to.publicKey), null)
        val handle = KeysetHandle.newBuilder().addEntry(KeysetHandle.importKey(publicKey).withRandomId().makePrimary()).build()
        val primitive = handle.getPrimitive(RegistryConfiguration.get(), HybridEncrypt::class.java)
        return SealedMessage(to.fingerprint, primitive.encrypt(payload, id.toByteArray()))
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
        } catch (_: SerializationException) {
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
