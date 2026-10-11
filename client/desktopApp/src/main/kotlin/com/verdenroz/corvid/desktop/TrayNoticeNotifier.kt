package com.verdenroz.corvid.desktop

import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.TrayState
import androidx.compose.ui.window.isTraySupported
import com.verdenroz.corvid.app.NewMailNotifier
import com.verdenroz.corvid.app.NoticeNotifier
import java.io.IOException

/**
 * Notifies through the window's tray icon. A Linux desktop with no tray host, as a tiling window manager has,
 * gets `notify-send` instead; where neither exists the banner is all there is. A shown notification cannot be
 * withdrawn on either path, so [clear] does nothing.
 */
class TrayNoticeNotifier : NoticeNotifier, NewMailNotifier {
    @Volatile
    private var tray: TrayState? = null

    fun attach(state: TrayState?) {
        tray = state
    }

    override fun post(
        title: String,
        text: String,
    ) {
        if (isTraySupported) {
            tray?.sendNotification(Notification(title, text, Notification.Type.Info))
        } else {
            try {
                ProcessBuilder("notify-send", "--app-name=Corvid", title, text).start()
            } catch (e: IOException) {
                return
            }
        }
    }

    override fun show(
        title: String,
        text: String,
    ) = post(title, text)

    override fun clear() = Unit
}
