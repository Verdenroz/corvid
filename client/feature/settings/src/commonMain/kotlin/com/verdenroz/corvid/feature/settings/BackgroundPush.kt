package com.verdenroz.corvid.feature.settings

import com.verdenroz.corvid.core.prefs.PushStatus

/** The platform's background push registration, absent where the platform has none. */
interface BackgroundPush {
    /** Whether the platform can push through Google, directly or through microG. */
    fun available(): Boolean

    /** Asks for an endpoint that only the server's [vapid] key may push to; it arrives later and on its own. */
    fun register(vapid: String)

    fun unregister()
}

/** What the background notifications row says, and whether it reads as on. */
enum class BackgroundPushLine(val on: Boolean) {
    Off(false),
    Unavailable(false),
    Registering(true),
    Waiting(true),
    Sent(true),
    OffOnServer(true),
    NotApproved(true),
    Refused(true),
    Removing(false),
    Failed(false),
}

internal fun backgroundPushLine(
    status: PushStatus,
    unavailable: Boolean,
): BackgroundPushLine = if (unavailable) BackgroundPushLine.Unavailable else statusLine(status)

private fun statusLine(status: PushStatus): BackgroundPushLine =
    when (status) {
        PushStatus.Off -> BackgroundPushLine.Off
        PushStatus.Registering -> BackgroundPushLine.Registering
        PushStatus.Unsent -> BackgroundPushLine.Waiting
        PushStatus.Sent -> BackgroundPushLine.Sent
        PushStatus.OffOnServer -> BackgroundPushLine.OffOnServer
        PushStatus.NotApproved -> BackgroundPushLine.NotApproved
        PushStatus.Refused -> BackgroundPushLine.Refused
        PushStatus.Removing -> BackgroundPushLine.Removing
        PushStatus.Failed -> BackgroundPushLine.Failed
    }
