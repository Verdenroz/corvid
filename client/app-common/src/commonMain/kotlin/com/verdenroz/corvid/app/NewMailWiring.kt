package com.verdenroz.corvid.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.prefs.DevicePreferences
import com.verdenroz.corvid.core.prefs.PushStatus
import org.koin.compose.koinInject
import org.koin.core.scope.Scope

/**
 * The new-mail notification for as long as [scope] lives, quiet while the Inbox is on screen and while the server holds
 * this device's push, which shows each message itself. The caller runs [NewMail.check] on each mail change. A tapped notification, even one tapped while locked, takes [navigator] to the
 * Inbox, or to the digest a digest notification names.
 */
@Composable
internal fun rememberNewMail(
    scope: Scope,
    navigator: Navigator,
    preferences: DevicePreferences = koinInject(),
    notifier: NewMailNotifier = koinInject(),
    presence: AppPresence = koinInject(),
    permission: NotificationPermission = koinInject(),
): NewMail {
    val newMail = remember(scope) { NewMail(scope.get<MailboxRepository>()::inboxArrivals, notifier) }
    val settings = preferences.settings.collectAsState().value
    val enabled = settings.newMailNotifications
    val pushed = settings.push.status == PushStatus.Sent
    val inView by presence.inView.collectAsState()
    val inboxInView = inView && navigator.route == Route.Home(HomeTab.Inbox)
    LaunchedEffect(newMail, inboxInView, enabled, pushed) { newMail.attention(inboxInView, enabled, pushed) }
    LaunchedEffect(Unit) { if (enabled) permission.askOnce() }
    LaunchedEffect(navigator) { presence.deliverTo(navigator) }
    return newMail
}
