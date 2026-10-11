package com.verdenroz.corvid.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.prefs.DevicePreferences
import org.koin.compose.koinInject
import org.koin.core.scope.Scope

/**
 * The new-mail notification for as long as [scope] lives, quiet while the Inbox is on screen; the caller runs
 * [NewMail.check] on each mail change. A tapped notification brings [navigator] back to the Inbox.
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
    val enabled = preferences.settings.collectAsState().value.newMailNotifications
    val inView by presence.inView.collectAsState()
    val inboxInView = inView && navigator.route == Route.Home(HomeTab.Inbox)
    LaunchedEffect(newMail, inboxInView, enabled) { newMail.attention(inboxInView, enabled) }
    LaunchedEffect(Unit) { if (enabled) permission.askOnce() }
    LaunchedEffect(navigator) { presence.inboxRequests.collect { navigator.showInbox() } }
    return newMail
}
