package com.verdenroz.corvid

import android.app.Notification
import android.content.Context
import com.verdenroz.corvid.app.NoticeNotifier

private const val CHANNEL_ID = "server-notices"

/** Posts the server's notices, each replacing the last since the banner already counts them; without the notification permission Android drops them and the banner remains. */
class AndroidNoticeNotifier(private val context: Context) : NoticeNotifier {
    private val manager = context.notificationChannel(CHANNEL_ID, R.string.notice_channel)

    override fun post(
        title: String,
        text: String,
    ) {
        // A lock screen shows only the generic version, since the full text names addresses.
        val generic =
            context.notification(
                CHANNEL_ID,
                context.getString(R.string.app_name),
                context.getString(R.string.notice_generic),
            ).build()
        val notification =
            context.notification(CHANNEL_ID, title, text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(generic)
                .setContentIntent(context.openApp(0))
                .setAutoCancel(true)
                .build()
        manager.notify(NOTICE_ID, notification)
    }
}
