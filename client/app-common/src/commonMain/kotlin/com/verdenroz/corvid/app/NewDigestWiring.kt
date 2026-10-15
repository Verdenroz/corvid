package com.verdenroz.corvid.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.verdenroz.corvid.core.data.DigestRepository
import com.verdenroz.corvid.core.prefs.DevicePreferences
import com.verdenroz.corvid.feature.mailbox.OvernightOffer
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.core.scope.Scope

/** Looks for a new digest for as long as [scope] lives, and again on each return to the foreground and each digest push. */
@Composable
internal fun rememberNewDigest(
    scope: Scope,
    navigator: Navigator,
    preferences: DevicePreferences = koinInject(),
    backgroundDrop: BackgroundDropController = koinInject(),
    presence: AppPresence = koinInject(),
): NewDigest {
    val newDigest = remember(scope) { NewDigest(scope.get<DigestRepository>(), preferences) }
    LaunchedEffect(newDigest) { newDigest.run(merge(backgroundDrop.foregrounded, presence.digestArrivals)) }
    val route = navigator.route
    LaunchedEffect(newDigest, route) { if (route is Route.Digest) newDigest.opened(route.id) }
    return newDigest
}

@Composable
internal fun rememberOvernightOffer(
    newDigest: NewDigest,
    navigator: Navigator,
): OvernightOffer? {
    val entry by newDigest.entry.collectAsState()
    val scope = rememberCoroutineScope()
    return entry?.let { current ->
        OvernightOffer(
            current,
            onOpen = { navigator.open(Route.Digest(current.id)) },
            onHide = { scope.launch { newDigest.dismiss() } },
        )
    }
}
