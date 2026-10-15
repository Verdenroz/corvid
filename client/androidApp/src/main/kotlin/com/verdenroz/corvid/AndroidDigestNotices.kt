package com.verdenroz.corvid

import android.app.Notification
import android.app.PendingIntent
import android.content.Context

private const val DIGEST_CHANNEL = "digest"
private const val DIGEST_NOTICE_ID = 5
internal const val EXTRA_OPEN_DIGEST = "com.verdenroz.corvid.OPEN_DIGEST"

/** The pushed "digest is ready" notification, which carries no mail and opens the digest it names. */
class AndroidDigestNotices(private val context: Context) {
    private val manager =
        context.notificationChannel(DIGEST_CHANNEL, R.string.digest_channel) { lockscreenVisibility = Notification.VISIBILITY_SECRET }

    fun post(digestId: String) {
        val open = context.openApp(DIGEST_NOTICE_ID, PendingIntent.FLAG_UPDATE_CURRENT) { putExtra(EXTRA_OPEN_DIGEST, digestId) }
        val notification =
            context.notification(DIGEST_CHANNEL, context.getString(R.string.digest_ready), context.getString(R.string.app_name))
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        manager.notify(DIGEST_NOTICE_ID, notification)
    }
}
