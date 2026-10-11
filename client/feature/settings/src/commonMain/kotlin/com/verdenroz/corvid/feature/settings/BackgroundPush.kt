package com.verdenroz.corvid.feature.settings

import com.verdenroz.corvid.core.prefs.PushStatus
import com.verdenroz.corvid.core.prefs.PushSubscription

/** How a platform pushes new-mail previews to a device that is closed. */
enum class PushTransport {
    /** Through Google's push service, directly or through microG. */
    Google,

    /** Over a connection the app keeps to the server itself, where there is no Google service. */
    OwnConnection,
}

/** The platform's background push registration, absent where the platform has none. */
interface BackgroundPush {
    fun transport(): PushTransport

    /** [PushTransport.Google] only: asks for an endpoint that only the server's [vapid] key may push to, which arrives later. */
    fun register(vapid: String)

    /** [PushTransport.OwnConnection] only: makes this device's push keys, and asks to be left running in the background. */
    fun registerOwnConnection(): PushSubscription

    /** Forgets whatever [register] or [registerOwnConnection] made. */
    fun unregister()
}

/** What the background notifications row shows: where the push stands, and which transport it reads as. */
data class BackgroundPushState(
    val status: PushStatus,
    val ownConnection: Boolean,
) {
    val on: Boolean get() = status != PushStatus.Off && status != PushStatus.Removing && status != PushStatus.Failed
}
