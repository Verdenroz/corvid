package com.verdenroz.corvid

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.verdenroz.corvid.core.prefs.DevicePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

private const val CONNECTION_CHANNEL = "connection"
private const val CONNECTION_ID = 5

/**
 * Holds the connection to the server for a phone that has no Google service to push through. The notification can be
 * swiped away or its channel switched off without ending the service.
 */
class CorvidStreamService : Service() {
    private val preferences: DevicePreferences by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var stream: Job? = null

    // ServiceCompat drops the type below API 34, where the manifest's specialUse is not enforced.
    @SuppressLint("InlinedApi")
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        ServiceCompat.startForeground(this, CONNECTION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (stream == null) stream = scope.launch { PushStream(this@CorvidStreamService, preferences).run() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        notificationChannel(CONNECTION_CHANNEL, R.string.connection_channel) {
            importance = NotificationManager.IMPORTANCE_LOW
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        return notification(CONNECTION_CHANNEL, getString(R.string.app_name), getString(R.string.connection_text))
            .setOngoing(true)
            .setContentIntent(openApp(CONNECTION_ID))
            .build()
    }

    companion object {
        fun start(context: Context) {
            // Started from the background, the system refuses; the next launch or boot starts it.
            try {
                context.startForegroundService(Intent(context, CorvidStreamService::class.java))
            } catch (e: IllegalStateException) {
                return
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CorvidStreamService::class.java))
        }
    }
}
