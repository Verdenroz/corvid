package com.verdenroz.corvid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.verdenroz.corvid.app.NewMailNotifier

private const val CHANNEL_ID = "new-mail"
private const val NOTIFICATION_ID = 2
internal const val EXTRA_OPEN_INBOX = "com.verdenroz.corvid.OPEN_INBOX"

/** One new-mail notification, replaced as the count grows, in its own channel that the lock screen never shows. */
class AndroidNewMailNotifier(private val context: Context) : NewMailNotifier {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.new_mail_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            },
        )
    }

    override fun show(
        title: String,
        text: String,
    ) {
        val open =
            PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(EXTRA_OPEN_INBOX, true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_corvid)
                .setContentTitle(title)
                .setContentText(text)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun clear() = manager.cancel(NOTIFICATION_ID)
}
