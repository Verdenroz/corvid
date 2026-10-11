package com.verdenroz.corvid

import android.content.Context
import com.verdenroz.corvid.feature.settings.BackgroundPush
import org.unifiedpush.android.connector.UnifiedPush

/**
 * UnifiedPush through the embedded FCM distributor, which lives in this app and registers with Play Services or microG.
 * The connector lists it only where one of them is installed.
 */
class AndroidBackgroundPush(private val context: Context) : BackgroundPush {
    override fun available(): Boolean = context.packageName in UnifiedPush.getDistributors(context)

    override fun register(vapid: String) {
        UnifiedPush.saveDistributor(context, context.packageName)
        UnifiedPush.register(context, vapid = vapid)
    }

    override fun unregister() = UnifiedPush.unregister(context)
}
