package com.verdenroz.corvid.core.prefs

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

enum class ThemeChoice { System, Light, Dark }

enum class AutoLock(val minutes: Int) {
    OneMinute(1),
    FiveMinutes(5),
    FifteenMinutes(15),
    ThirtyMinutes(30),
    ;

    val timeout: Duration get() = minutes.minutes
}

/**
 * The last account signed in here, so the next sign-in skips discovery. Addresses only: the tokens
 * stay in memory. [email] is absent when the account was entered by server and issuer.
 */
data class RememberedAccount(
    val server: String,
    val issuer: String,
    val email: String? = null,
)

/** The endpoint of a device that takes its pushes over its own connection to the server instead of through Google. */
const val OWN_CONNECTION = "corvid-stream"

/** A push endpoint and the RFC 8291 public keys that encrypt to it, base64url as the connector gives them. */
data class PushSubscription(
    val url: String,
    val p256dh: String,
    val auth: String,
) {
    val ownConnection: Boolean get() = url == OWN_CONNECTION
}

/** Where background notifications stand between the platform, this device and the server. */
enum class PushStatus {
    Off,

    /** Registered with the platform, which has not handed over an endpoint yet. */
    Registering,

    /** An endpoint is held that the server has not taken yet. */
    Unsent,
    Sent,

    /** The server has no push or no device routes. */
    OffOnServer,

    /** The server does not know this device's name. */
    NotApproved,

    /** The server refused the endpoint as not one of Google's. */
    Refused,

    /** Turned off here; the server may hold the endpoint until a removal reaches it. */
    Removing,

    /** The server could not be reached to start, or refused the connection's token. */
    Failed,

    /** Google's push service could not register this app, so turning it on again keeps its own connection. */
    GoogleUnavailable,
}

/**
 * [subscription] is held from the platform's answer until background notifications are turned off. [streamToken] is what
 * the server issued for reading an own connection, and exists only once the server has taken that subscription.
 */
data class PushSettings(
    val status: PushStatus = PushStatus.Off,
    val subscription: PushSubscription? = null,
    val streamToken: String? = null,
)

/**
 * Choices that belong to this device and hold no mail, so they live in a plain file. [noticesSeen] is the newest
 * server notice id shown here; [newMailNotifications] says whether new Inbox mail posts a content-free notification.
 */
data class DeviceSettings(
    val theme: ThemeChoice = ThemeChoice.System,
    val autoLock: AutoLock = AutoLock.FiveMinutes,
    val noticesSeen: Long = 0,
    val newMailNotifications: Boolean = true,
    val account: RememberedAccount? = null,
    val push: PushSettings = PushSettings(),
)
