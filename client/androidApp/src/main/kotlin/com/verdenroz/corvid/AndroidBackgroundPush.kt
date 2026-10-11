package com.verdenroz.corvid

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri
import com.verdenroz.corvid.core.prefs.OWN_CONNECTION
import com.verdenroz.corvid.core.prefs.PushSubscription
import com.verdenroz.corvid.feature.settings.BackgroundPush
import com.verdenroz.corvid.feature.settings.PushTransport
import org.unifiedpush.android.connector.UnifiedPush

private const val GOOGLE_SERVICES = "com.google.android.gms"

/**
 * Push through the embedded FCM distributor, which lives in this app and registers with Play Services or microG; the
 * connector lists it only where one of them is installed. Elsewhere the app keeps its own connection.
 */
class AndroidBackgroundPush(private val context: Context) : BackgroundPush {
    private val keyStore = StreamKeyStore(context)

    override fun transport(): PushTransport =
        if (context.packageName in UnifiedPush.getDistributors(context) && googleServicesEnabled()) {
            PushTransport.Google
        } else {
            PushTransport.OwnConnection
        }

    // The connector counts a disabled Play Services as installed.
    private fun googleServicesEnabled(): Boolean =
        try {
            context.packageManager.getApplicationInfo(GOOGLE_SERVICES, 0).enabled
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    override fun register(vapid: String) {
        UnifiedPush.saveDistributor(context, context.packageName)
        UnifiedPush.register(context, vapid = vapid)
    }

    override fun registerOwnConnection(): PushSubscription {
        val keys = keyStore.create()
        askToStayRunning()
        return PushSubscription(OWN_CONNECTION, keys.p256dh, keys.authSecret)
    }

    override fun unregister() {
        UnifiedPush.unregister(context)
        keyStore.delete()
    }

    // The app is not distributed through Play, whose policy is what this check enforces.
    @SuppressLint("BatteryLife")
    private fun askToStayRunning() {
        if (context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)) return
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
