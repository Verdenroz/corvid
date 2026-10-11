package com.verdenroz.corvid

import com.verdenroz.corvid.core.common.di.applicationScopeQualifier
import com.verdenroz.corvid.core.prefs.DevicePreferences
import com.verdenroz.corvid.core.prefs.PushSubscription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * The receiving side of background notifications, fed by the embedded FCM distributor. Runs while the app is locked, so
 * it reads no key and calls no server: an endpoint is only recorded here, for the unlocked app to send.
 */
class CorvidPushService : PushService() {
    private val preferences: DevicePreferences by inject()
    private val appScope: CoroutineScope by inject(applicationScopeQualifier)
    private val previews by lazy { AndroidMailPreviews(this) }

    override fun onNewEndpoint(
        endpoint: PushEndpoint,
        instance: String,
    ) {
        val keys = endpoint.pubKeySet
        appScope.launch {
            if (keys == null) {
                preferences.setPushGoogleUnavailable()
            } else {
                preferences.setPushSubscription(PushSubscription(endpoint.url, keys.pubKey, keys.auth))
            }
        }
    }

    override fun onMessage(
        message: PushMessage,
        instance: String,
    ) {
        previews.show(message.content, message.decrypted)
    }

    override fun onRegistrationFailed(
        reason: FailedReason,
        instance: String,
    ) {
        appScope.launch { preferences.setPushGoogleUnavailable() }
    }

    override fun onUnregistered(instance: String) {
        appScope.launch { preferences.removePush() }
    }
}
