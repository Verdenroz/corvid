package com.verdenroz.corvid.core.data.outbox

import java.security.MessageDigest
import java.util.Base64

private const val ENVELOPE_SCHEMA = "1"

internal sealed interface EnvelopeRead {
    class Valid(val message: SealedMessage) : EnvelopeRead

    data object Corrupt : EnvelopeRead

    class Foreign(val found: String) : EnvelopeRead
}

internal fun encodeEnvelope(message: SealedMessage): ByteArray =
    listOf(
        "corvid-outbox $ENVELOPE_SCHEMA",
        "key ${message.keyFingerprint}",
        "sha256 ${sha256(message.bytes)}",
        Base64.getEncoder().encodeToString(message.bytes),
    ).joinToString("\n", postfix = "\n").toByteArray()

internal fun decodeEnvelope(bytes: ByteArray): EnvelopeRead {
    val lines = bytes.decodeToString().lines()
    val schema = lines.value(0, "corvid-outbox") ?: return EnvelopeRead.Corrupt
    if (schema != ENVELOPE_SCHEMA) return EnvelopeRead.Foreign(schema)
    val key = lines.value(1, "key")
    val checksum = lines.value(2, "sha256")
    val sealed = lines.getOrNull(3)?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
    return if (key == null || sealed == null || checksum != sha256(sealed)) {
        EnvelopeRead.Corrupt
    } else {
        EnvelopeRead.Valid(SealedMessage(key, sealed))
    }
}

private fun List<String>.value(
    index: Int,
    name: String,
): String? = getOrNull(index)?.takeIf { it.startsWith("$name ") }?.removePrefix("$name ")

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
