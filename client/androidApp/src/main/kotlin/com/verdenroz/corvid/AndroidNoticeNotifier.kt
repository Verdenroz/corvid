package com.verdenroz.corvid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.verdenroz.corvid.app.NoticeNotifier

private const val CHANNEL_ID = "server-notices"
private const val NOTIFICATION_ID = 1

/** Posts the server's notices, each replacing the last since the banner already counts them; without the notification permission Android drops them and the banner remains. */
class AndroidNoticeNotifier(private val context: Context) : NoticeNotifier {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.notice_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    override fun post(
        title: String,
        text: String,
    ) {
        val open =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        // A lock screen shows only the generic version, since the full text names addresses.
        val generic =
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_corvid)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(R.string.notice_generic))
                .build()
        val notification =
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_corvid)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(generic)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}
