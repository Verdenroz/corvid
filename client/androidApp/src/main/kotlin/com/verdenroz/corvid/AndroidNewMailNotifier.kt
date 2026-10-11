package com.verdenroz.corvid

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import com.verdenroz.corvid.app.NewMailNotifier

private const val CHANNEL_ID = "new-mail"
private const val NOTIFICATION_ID = 2
internal const val EXTRA_OPEN_INBOX = "com.verdenroz.corvid.OPEN_INBOX"

/** One new-mail notification, replaced as the count grows, in its own channel that the lock screen never shows. */
class AndroidNewMailNotifier(private val context: Context) : NewMailNotifier {
    private val manager =
        context.notificationChannel(CHANNEL_ID, R.string.new_mail_channel) { lockscreenVisibility = Notification.VISIBILITY_SECRET }

    override fun post(
        title: String,
        text: String,
    ) {
        val open = context.openApp(NOTIFICATION_ID, PendingIntent.FLAG_UPDATE_CURRENT) { putExtra(EXTRA_OPEN_INBOX, true) }
        val notification =
            context.notification(CHANNEL_ID, title, text)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun clear() = manager.cancel(NOTIFICATION_ID)
}
