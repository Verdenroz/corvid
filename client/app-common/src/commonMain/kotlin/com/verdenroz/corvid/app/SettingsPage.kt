package com.verdenroz.corvid.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.verdenroz.corvid.core.auth.AuthState
import com.verdenroz.corvid.core.auth.PasskeyRepository
import com.verdenroz.corvid.core.auth.TokenSource
import com.verdenroz.corvid.core.data.DigestRepository
import com.verdenroz.corvid.core.data.UpdateRepository
import com.verdenroz.corvid.core.domain.ApproveWithKey
import com.verdenroz.corvid.core.domain.EnrollPasskey
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.keyring.DeviceApprovalRepository
import com.verdenroz.corvid.core.keyring.PushEndpointRepository
import com.verdenroz.corvid.core.model.Identity
import com.verdenroz.corvid.core.prefs.DevicePreferences
import com.verdenroz.corvid.feature.settings.AboutViewModel
import com.verdenroz.corvid.feature.settings.AccountFacts
import com.verdenroz.corvid.feature.settings.ApproveDeviceScreen
import com.verdenroz.corvid.feature.settings.ApproveDeviceViewModel
import com.verdenroz.corvid.feature.settings.BackgroundPush
import com.verdenroz.corvid.feature.settings.PasskeysScreen
import com.verdenroz.corvid.feature.settings.PasskeysViewModel
import com.verdenroz.corvid.feature.settings.SettingsScreen
import com.verdenroz.corvid.feature.settings.SettingsViewModel
import org.koin.compose.getKoin
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
    permission: NotificationPermission = koinInject(),
    backgroundPush: BackgroundPush? = getKoin().getOrNull(),
) {
    val viewModel =
        models.of(entryKey, SettingsViewModel::class) {
            SettingsViewModel(
                preferences,
                session.onLock,
                session.onSignOut,
                permission::ask,
                backgroundPush,
                scope.get<PushEndpointRepository>()::vapidKey,
            )
        }
    val about = models.of(entryKey, AboutViewModel::class) { AboutViewModel(scope.get<UpdateRepository>()) }
    val account =
        AccountFacts(
            name = session.identity?.name?.takeIf { it.isNotBlank() },
            address = session.identity?.email,
            server = session.hosts.server,
        )
    val digests = remember(scope) { scope.get<DigestRepository>() }
    val digestOffered by produceState(false, digests) {
        value =
            try {
                digests.latest() != null
            } catch (e: JmapException) {
                false
            }
    }
    SettingsScreen(
        viewModel,
        about,
        APP_VERSION,
        session.updates.state,
        session.updates::update,
        account,
        onBack = navigator::back,
        onOpenFilters = { navigator.push(Route.Filters) },
        onOpenNotices = { navigator.push(Route.Notices) },
        onOpenDigest = { navigator.push(Route.Digest()) }.takeIf { digestOffered },
        onOpenApproveDevice = { navigator.push(Route.ApproveDevice) },
        onOpenPasskeys = { navigator.push(Route.Passkeys) },
    )
}

@Composable
internal fun ApproveDevicePage(
    scope: Scope,
    navigator: Navigator,
    models: EntryModels,
    entryKey: String,
    approveWithKey: ApproveWithKey = koinInject(),
) {
    val viewModel =
        models.of(entryKey, ApproveDeviceViewModel::class) { ApproveDeviceViewModel(scope.get<DeviceApprovalRepository>(), approveWithKey) }
    ApproveDeviceScreen(viewModel, onBack = navigator::back)
}

@Composable
internal fun PasskeysPage(
    scope: Scope,
    navigator: Navigator,
    models: EntryModels,
    entryKey: String,
    approveWithKey: ApproveWithKey = koinInject(),
    enrollPasskey: EnrollPasskey = koinInject(),
    tokenSource: TokenSource = koinInject(),
) {
    val viewModel =
        models.of(entryKey, PasskeysViewModel::class) {
            val server = checkNotNull((tokenSource.state.value as? AuthState.SignedIn)?.server) { "not signed in" }
            PasskeysViewModel(scope.get<PasskeyRepository>(), approveWithKey, enrollPasskey, server)
        }
    PasskeysScreen(viewModel, onBack = navigator::back)
}
