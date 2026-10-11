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

    /**
     * Shows one push. A body the connector could not decrypt is ignored, since anyone who can reach the push host can
     * post to the endpoint; one that decrypted but cannot be read shows a generic notice.
     */
    fun show(
        content: ByteArray,
        decrypted: Boolean,
    ) {
        if (decrypted) post(readMailPreview(content))
    }

    private fun post(preview: MailPreview?) {
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
        val summary = generic().setGroupSummary(true).setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
        manager.notify(preview?.id ?: UNREADABLE_TAG, PREVIEW_ID, shown.inGroup(open).build())
        manager.notify(SUMMARY_ID, summary.inGroup(open).build())
    }

    private fun Notification.Builder.inGroup(open: PendingIntent): Notification.Builder =
        setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(generic().build())
            .setGroup(PREVIEW_GROUP)
            .setContentIntent(open)
            .setAutoCancel(true)

    private fun generic(): Notification.Builder =
        context.notification(NEW_MAIL_CHANNEL, context.getString(R.string.app_name), context.getString(R.string.new_mail_channel))
}
