package com.verdenroz.corvid.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.verdenroz.corvid.core.keyring.PushEndpointRepository
import com.verdenroz.corvid.core.prefs.DevicePreferences
import org.koin.compose.koinInject
import org.koin.core.scope.Scope

/** Keeps the server's copy of this device's push endpoint in step for as long as [scope] lives. */
@Composable
internal fun FollowPushEndpoint(
    scope: Scope,
    hosts: AccountHosts,
    preferences: DevicePreferences = koinInject(),
) {
    LaunchedEffect(scope) {
        val name = preferences.deviceName(hosts.server) ?: return@LaunchedEffect
        PushSync(scope.get<PushEndpointRepository>(), preferences, name).run()
    }
}
