package com.verdenroz.corvid

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import com.verdenroz.corvid.app.NewMailNotifier

internal const val NEW_MAIL_CHANNEL = "new-mail"
internal const val PREVIEW_GROUP = "com.verdenroz.corvid.NEW_MAIL"
internal const val EXTRA_OPEN_INBOX = "com.verdenroz.corvid.OPEN_INBOX"

/** The new-mail channel, which the lock screen never shows. */
internal fun Context.newMailChannel(): NotificationManager =
    notificationChannel(NEW_MAIL_CHANNEL, R.string.new_mail_channel) { lockscreenVisibility = Notification.VISIBILITY_SECRET }

/** One new-mail notification, replaced as the count grows; clearing it clears the pushed previews too. */
class AndroidNewMailNotifier(private val context: Context) : NewMailNotifier {
    private val manager = context.newMailChannel()

    override fun post(
        title: String,
        text: String,
    ) {
        val open = context.openApp(NEW_MAIL_ID, PendingIntent.FLAG_UPDATE_CURRENT) { putExtra(EXTRA_OPEN_INBOX, true) }
        val notification =
            context.notification(NEW_MAIL_CHANNEL, title, text)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        manager.notify(NEW_MAIL_ID, notification)
    }

    override fun clear() {
        manager.cancel(NEW_MAIL_ID)
        manager.activeNotifications.filter { it.notification.group == PREVIEW_GROUP }.forEach { manager.cancel(it.tag, it.id) }
    }
}
