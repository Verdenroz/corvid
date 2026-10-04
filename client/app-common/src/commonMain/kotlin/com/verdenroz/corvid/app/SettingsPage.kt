package com.verdenroz.corvid.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.verdenroz.corvid.core.data.UpdateRepository
import com.verdenroz.corvid.core.model.Identity
import com.verdenroz.corvid.core.prefs.DevicePreferences
import com.verdenroz.corvid.feature.settings.AccountFacts
import com.verdenroz.corvid.feature.settings.SettingsScreen
import com.verdenroz.corvid.feature.settings.SettingsViewModel
import org.koin.core.scope.Scope

internal class SettingsSession(
    val hosts: AccountHosts,
    val identity: Identity?,
    val onLock: () -> Unit,
    val onSignOut: () -> Unit,
)

@Composable
internal fun SettingsPage(
    preferences: DevicePreferences,
    scope: Scope,
    session: SettingsSession,
    navigator: Navigator,
) {
    val viewModel = remember(preferences) { SettingsViewModel(preferences, session.onLock, session.onSignOut) }
    val account =
        AccountFacts(
            name = session.identity?.name?.takeIf { it.isNotBlank() },
            address = session.identity?.email,
            server = session.hosts.server,
            identityProvider = session.hosts.identityProvider,
        )
    SettingsScreen(
        viewModel,
        remember(scope) {
            scope.get<UpdateRepository>()
        },
        APP_VERSION,
        account,
        onBack = navigator::back,
        onOpenFilters = { navigator.push(Route.Filters) },
    )
}
