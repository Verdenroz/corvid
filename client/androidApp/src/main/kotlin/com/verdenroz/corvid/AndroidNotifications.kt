package com.verdenroz.corvid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes

/** The notification manager, with the channel [id] named by [name] created on it; [configure] adjusts the channel first. */
internal fun Context.notificationChannel(
    id: String,
    @StringRes name: Int,
    configure: NotificationChannel.() -> Unit = {},
): NotificationManager =
    getSystemService(NotificationManager::class.java).apply {
        createNotificationChannel(NotificationChannel(id, getString(name), NotificationManager.IMPORTANCE_DEFAULT).apply(configure))
    }

internal fun Context.notification(
    channelId: String,
    title: String,
    text: String,
): Notification.Builder =
    Notification.Builder(this, channelId)
        .setSmallIcon(R.drawable.ic_stat_corvid)
        .setContentTitle(title)
        .setContentText(text)

/** Brings the running app forward; [extras] add to the intent. */
internal fun Context.openApp(
    requestCode: Int,
    flags: Int = 0,
    extras: Intent.() -> Unit = {},
): PendingIntent =
    PendingIntent.getActivity(
        this,
        requestCode,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).apply(extras),
        PendingIntent.FLAG_IMMUTABLE or flags,
    )
