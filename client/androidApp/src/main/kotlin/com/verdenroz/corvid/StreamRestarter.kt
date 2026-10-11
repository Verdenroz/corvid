package com.verdenroz.corvid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.verdenroz.corvid.core.prefs.DevicePreferences
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Brings the own connection back after a reboot or an update of the app, which end the service. */
class StreamRestarter : BroadcastReceiver(), KoinComponent {
    private val preferences: DevicePreferences by inject()

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val restart = intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (restart && preferences.settings.value.push.streamToken != null) CorvidStreamService.start(context)
    }
}
