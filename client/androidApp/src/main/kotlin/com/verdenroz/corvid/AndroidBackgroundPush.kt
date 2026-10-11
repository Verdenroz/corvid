package com.verdenroz.corvid

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri
import com.verdenroz.corvid.core.prefs.OWN_CONNECTION
import com.verdenroz.corvid.core.prefs.PushSubscription
import com.verdenroz.corvid.feature.settings.BackgroundPush
import com.verdenroz.corvid.feature.settings.PushTransport
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.keys.DefaultKeyManager

/**
 * Push through the embedded FCM distributor, which lives in this app and registers with Play Services or microG; the
 * connector lists it only where one of them is installed. Elsewhere the app keeps its own connection, whose keys the
 * connector's key manager holds, wrapped by the Android keystore.
 */
class AndroidBackgroundPush(private val context: Context) : BackgroundPush {
    private val keys = DefaultKeyManager(context)

    override fun transport(): PushTransport =
        if (context.packageName in UnifiedPush.getDistributors(context)) PushTransport.Google else PushTransport.OwnConnection

    override fun register(vapid: String) {
        UnifiedPush.saveDistributor(context, context.packageName)
        UnifiedPush.register(context, vapid = vapid)
    }

    override fun registerOwnConnection(): PushSubscription {
        keys.delete(STREAM_KEYS)
        keys.generate(STREAM_KEYS)
        val published = checkNotNull(keys.getPublicKeySet(STREAM_KEYS)) { "the stream keys were just generated" }
        askToStayRunning()
        return PushSubscription(OWN_CONNECTION, published.pubKey, published.auth)
    }

    override fun unregister() {
        UnifiedPush.unregister(context)
        keys.delete(STREAM_KEYS)
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
