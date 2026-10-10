package com.verdenroz.corvid.app

import androidx.compose.runtime.Composable
import com.verdenroz.corvid.core.data.UpdateRepository
import com.verdenroz.corvid.core.domain.MasterSignIn
import com.verdenroz.corvid.core.keyring.DeviceApprovalRepository
import com.verdenroz.corvid.core.model.Identity
import com.verdenroz.corvid.core.prefs.DevicePreferences
import com.verdenroz.corvid.feature.settings.AboutViewModel
import com.verdenroz.corvid.feature.settings.AccountFacts
import com.verdenroz.corvid.feature.settings.AppUpdateControl
import com.verdenroz.corvid.feature.settings.ApproveDeviceScreen
import com.verdenroz.corvid.feature.settings.ApproveDeviceViewModel
import com.verdenroz.corvid.feature.settings.SettingsScreen
import com.verdenroz.corvid.feature.settings.SettingsViewModel
import org.koin.compose.koinInject
import org.koin.core.scope.Scope

internal class SettingsSession(
    val hosts: AccountHosts,
    val identity: Identity?,
    val updates: AppUpdates,
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
        AppUpdateControl(session.updates.state, session.updates::update),
        account,
        onBack = navigator::back,
        onOpenFilters = { navigator.push(Route.Filters) },
        onOpenNotices = { navigator.push(Route.Notices) },
        onOpenApproveDevice = { navigator.push(Route.ApproveDevice) },
    )
}

@Composable
internal fun ApproveDevicePage(
    scope: Scope,
    navigator: Navigator,
    models: EntryModels,
    entryKey: String,
    masterSignIn: MasterSignIn = koinInject(),
) {
    val viewModel =
        models.of(entryKey, ApproveDeviceViewModel::class) { ApproveDeviceViewModel(scope.get<DeviceApprovalRepository>(), masterSignIn) }
    ApproveDeviceScreen(viewModel, onBack = navigator::back)
}
