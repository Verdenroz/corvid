package com.verdenroz.corvid.app

import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.keyring.PushAnswer
import com.verdenroz.corvid.core.keyring.PushEndpointRepository
import com.verdenroz.corvid.core.prefs.DevicePreferences
import com.verdenroz.corvid.core.prefs.PushSettings
import com.verdenroz.corvid.core.prefs.PushStatus
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Hands the server this device's push endpoint while the app is unlocked: whatever is held when [run] starts, since
 * the server drops an endpoint FCM no longer knows, then each new endpoint and each removal as it happens. A server it
 * cannot reach leaves the state as it was, for the next unlock.
 */
class PushSync(
    private val endpoints: PushEndpointRepository,
    private val preferences: DevicePreferences,
    private val deviceName: String,
) {
    suspend fun run() {
        preferences.settings.map { it.push }.distinctUntilChanged().collectIndexed { index, push ->
            if (index == 0 && !push.holdsOwnConnection || push.status == PushStatus.Unsent || push.status == PushStatus.Removing) sync(push)
        }
    }

    private suspend fun sync(push: PushSettings) {
        val subscription = push.subscription
        try {
            if (subscription != null) {
                val result = endpoints.store(deviceName, subscription.url, subscription.p256dh, subscription.auth)
                preferences.recordPushAnswer(subscription, result.answer.status(), result.token)
            } else if (push.status == PushStatus.Removing) {
                endpoints.remove(deviceName)
                preferences.finishPushRemoval()
            }
        } catch (e: JmapException) {
            return
        }
    }

    /** Registering an own connection again would replace the token the open stream reads with. */
    private val PushSettings.holdsOwnConnection get() = subscription?.ownConnection == true && streamToken != null

    private fun PushAnswer.status(): PushStatus =
        when (this) {
            PushAnswer.Accepted -> PushStatus.Sent
            PushAnswer.OffOnServer -> PushStatus.OffOnServer
            PushAnswer.NotApproved -> PushStatus.NotApproved
            PushAnswer.Refused -> PushStatus.Refused
        }
}
