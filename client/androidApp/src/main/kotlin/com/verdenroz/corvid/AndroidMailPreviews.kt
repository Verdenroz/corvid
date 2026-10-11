package com.verdenroz.corvid

import android.app.Notification
import android.app.PendingIntent
import android.content.Context

private const val PREVIEW_ID = 3
private const val SUMMARY_ID = 4
private const val UNREADABLE_TAG = "unreadable"

/**
 * The pushed new-mail notifications, one per message tagged with its id, grouped under a summary on the new-mail
 * channel. Each names sender and subject; the lock screen gets only the public version.
 */
class AndroidMailPreviews(private val context: Context) {
    private val manager = context.newMailChannel()

    /** A null [preview] posts a generic one. */
    fun post(preview: MailPreview?) {
        val open = context.openApp(PREVIEW_ID, PendingIntent.FLAG_UPDATE_CURRENT) { putExtra(EXTRA_OPEN_INBOX, true) }
        val noSubject = context.getString(R.string.new_mail_no_subject)
        val noSender = context.getString(R.string.new_mail_no_sender)
        val shown =
            if (preview == null) {
                generic()
            } else {
                context.notification(NEW_MAIL_CHANNEL, preview.title.ifBlank { noSender }, preview.text(noSubject))
                    .setStyle(Notification.BigTextStyle().bigText(preview.bigText(noSubject)))
            }
        val notification =
            shown
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(generic().build())
                .setGroup(PREVIEW_GROUP)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        val summary =
            generic()
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(generic().build())
                .setGroup(PREVIEW_GROUP)
                .setGroupSummary(true)
                .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        manager.notify(preview?.id ?: UNREADABLE_TAG, PREVIEW_ID, notification)
        manager.notify(SUMMARY_ID, summary)
    }

    private fun generic(): Notification.Builder =
        context.notification(NEW_MAIL_CHANNEL, context.getString(R.string.app_name), context.getString(R.string.new_mail_channel))
}
