package com.verdenroz.corvid

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val KEYSTORE = "AndroidKeyStore"
private const val WRAPPING_KEY = "corvid-stream-keys"
private const val PREFERENCES = "stream-keys"
private const val GCM_TAG_BITS = 128

/**
 * Keeps the own connection's [StreamKeys] in app storage, the private key sealed by an Android keystore key that
 * never leaves the secure hardware.
 */
internal class StreamKeyStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** Replaces any earlier keys with new ones. */
    fun create(): StreamKeys {
        val keys = StreamKeys.generate()
        val (private, public, auth) = keys.encoded()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrappingKey()) }
        preferences.edit {
            putString("private", encode(cipher.iv + cipher.doFinal(private)))
            putString("public", encode(public))
            putString("auth", encode(auth))
        }
        return keys
    }

    fun load(): StreamKeys? {
        val sealed = preferences.getString("private", null)?.let(::decode) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(GCM_TAG_BITS, sealed, 0, IV_BYTES))
        val private = cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
        val public = decode(preferences.getString("public", null) ?: return null)
        val auth = decode(preferences.getString("auth", null) ?: return null)
        return StreamKeys.decode(private, public, auth)
    }

    fun delete() {
        preferences.edit { clear() }
    }

    private fun wrappingKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(WRAPPING_KEY, null) as? SecretKey)?.let { return it }
        val spec =
            KeyGenParameterSpec.Builder(WRAPPING_KEY, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
    }

    private fun encode(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    private fun decode(text: String) = Base64.getDecoder().decode(text)

    private companion object {
        const val IV_BYTES = 12
    }
}
