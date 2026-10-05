package com.verdenroz.corvid.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Spelled as the server writes it. */
@Serializable
enum class NoticeKind {
    @SerialName("signIn")
    SignIn,

    @SerialName("tooManyAttempts")
    TooManyAttempts,

    @SerialName("ban")
    Ban,

    @SerialName("update")
    Update,
}

/** One thing the server wants known about itself. [id] only grows, so the newest notice has the largest. */
data class Notice(
    val id: Long,
    val kind: NoticeKind,
    val at: Instant,
    val title: String,
    val detail: String,
)
