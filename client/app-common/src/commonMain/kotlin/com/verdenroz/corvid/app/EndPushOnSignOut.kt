package com.verdenroz.corvid.app

import com.verdenroz.corvid.core.prefs.DevicePreferences
import com.verdenroz.corvid.core.prefs.PushStatus
import com.verdenroz.corvid.feature.settings.BackgroundPush
import kotlinx.coroutines.flow.Flow

/**
 * Ends background notifications when the session goes from signed in to signed out, however it got there: the
 * settings button, a refused refresh, or a 401. A device that stayed registered would go on showing the account's mail.
 */
suspend fun endPushOnSignOut(
    signedIn: Flow<Boolean>,
    push: BackgroundPush,
    preferences: DevicePreferences,
) {
    var was = false
    signedIn.collect { now ->
        if (was && !now && preferences.settings.value.push.status != PushStatus.Off) {
            push.unregister()
            preferences.removePush()
        }
        was = now
    }
}
