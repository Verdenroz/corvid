package com.verdenroz.corvid.app

import androidx.compose.runtime.Composable
import com.verdenroz.corvid.core.data.UpdateRepository
import com.verdenroz.corvid.core.model.Identity
import com.verdenroz.corvid.core.prefs.DevicePreferences
import com.verdenroz.corvid.feature.settings.AboutViewModel
import com.verdenroz.corvid.feature.settings.AccountFacts
import com.verdenroz.corvid.feature.settings.SettingsScreen
import com.verdenroz.corvid.feature.settings.SettingsViewModel
import org.koin.compose.koinInject
import org.koin.core.scope.Scope

internal class SettingsSession(
    val hosts: AccountHosts,
    val identity: Identity?,
    val onLock: () -> Unit,
    val onSignOut: () -> Unit,
)

@Composable
internal fun SettingsPage(
    scope: Scope,
    session: SettingsSession,
    navigator: Navigator,
    models: EntryModels,
    entryKey: String,
    preferences: DevicePreferences = koinInject(),
) {
    val viewModel = models.of(entryKey, SettingsViewModel::class) { SettingsViewModel(preferences, session.onLock, session.onSignOut) }
    val about = models.of(entryKey, AboutViewModel::class) { AboutViewModel(scope.get<UpdateRepository>()) }
    val account =
        AccountFacts(
            name = session.identity?.name?.takeIf { it.isNotBlank() },
            address = session.identity?.email,
            server = session.hosts.server,
            identityProvider = session.hosts.identityProvider,
        )
    SettingsScreen(
        viewModel,
        about,
        APP_VERSION,
        account,
        onBack = navigator::back,
        onOpenFilters = { navigator.push(Route.Filters) },
        onOpenNotices = { navigator.push(Route.Notices) },
    )
}
