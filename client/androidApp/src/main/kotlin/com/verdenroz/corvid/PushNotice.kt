package com.verdenroz.corvid

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private class PushedHead(val v: Int, val kind: String? = null, val id: String? = null)

private val noticeJson = Json { ignoreUnknownKeys = true }

/** What a decrypted push body asks for. */
sealed interface PushNotice {
    data class Mail(val preview: MailPreview) : PushNotice

    data class Digest(val id: String) : PushNotice

    /** A kind this app does not know; it shows nothing. */
    data object Ignored : PushNotice

    /** A body this app cannot read, shown as a generic new-mail notice. */
    data object Unreadable : PushNotice
}

fun readPushNotice(content: ByteArray): PushNotice {
    val head =
        try {
            noticeJson.decodeFromString<PushedHead>(content.decodeToString())
        } catch (e: IllegalArgumentException) {
            return PushNotice.Unreadable
        }
    return when (head.v) {
        1 -> readMailPreview(content)?.let(PushNotice::Mail) ?: PushNotice.Unreadable
        2 -> head.id?.takeIf { head.kind == "digest" }?.let(PushNotice::Digest) ?: PushNotice.Ignored
        else -> PushNotice.Unreadable
    }
}
