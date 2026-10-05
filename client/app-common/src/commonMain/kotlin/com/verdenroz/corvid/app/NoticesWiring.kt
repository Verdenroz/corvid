package com.verdenroz.corvid.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.verdenroz.corvid.app.shell.NoticeBannerBar
import com.verdenroz.corvid.core.data.NoticeRepository
import com.verdenroz.corvid.core.prefs.DevicePreferences
import org.koin.compose.koinInject
import org.koin.core.scope.Scope

/** Follows the server's notices for as long as [scope] lives, polling again each time the app returns to the foreground. */
@Composable
internal fun rememberNotices(
    scope: Scope,
    preferences: DevicePreferences = koinInject(),
    notifier: NoticeNotifier = koinInject(),
    backgroundDrop: BackgroundDropController = koinInject(),
): Notices {
    val notices = remember(scope) { Notices(scope.get<NoticeRepository>(), preferences, notifier) }
    LaunchedEffect(notices) { notices.run(backgroundDrop.foregrounded) }
    return notices
}

@Composable
internal fun NoticesBanner(
    notices: Notices,
    navigator: Navigator,
) {
    val banner by notices.banner.collectAsState()
    banner?.let { current ->
        NoticeBannerBar(
            current,
            onOpen = {
                notices.dismiss()
                if (navigator.route != Route.Notices) navigator.push(Route.Notices)
            },
            onDismiss = notices::dismiss,
        )
    }
}
