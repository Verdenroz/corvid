package com.verdenroz.corvid.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.designsystem.CorvidSpacing
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.CorvidIcons
import com.verdenroz.corvid.core.designsystem.component.PillButton
import com.verdenroz.corvid.core.prefs.AutoLock
import com.verdenroz.corvid.core.prefs.ThemeChoice
import com.verdenroz.corvid.core.ui.DiscardQuestion
import com.verdenroz.corvid.core.ui.LocalWideLayout
import com.verdenroz.corvid.core.ui.PageHeader
import com.verdenroz.corvid.feature.settings.resources.Res
import com.verdenroz.corvid.feature.settings.resources.settings_account
import com.verdenroz.corvid.feature.settings.resources.settings_address
import com.verdenroz.corvid.feature.settings.resources.settings_appearance
import com.verdenroz.corvid.feature.settings.resources.settings_approve_detail
import com.verdenroz.corvid.feature.settings.resources.settings_approve_row
import com.verdenroz.corvid.feature.settings.resources.settings_auto_lock
import com.verdenroz.corvid.feature.settings.resources.settings_auto_lock_detail
import com.verdenroz.corvid.feature.settings.resources.settings_auto_lock_option
import com.verdenroz.corvid.feature.settings.resources.settings_back
import com.verdenroz.corvid.feature.settings.resources.settings_cancel
import com.verdenroz.corvid.feature.settings.resources.settings_filters
import com.verdenroz.corvid.feature.settings.resources.settings_filters_detail
import com.verdenroz.corvid.feature.settings.resources.settings_filters_row
import com.verdenroz.corvid.feature.settings.resources.settings_identity_provider
import com.verdenroz.corvid.feature.settings.resources.settings_lock_now
import com.verdenroz.corvid.feature.settings.resources.settings_manage_sign_in
import com.verdenroz.corvid.feature.settings.resources.settings_manage_sign_in_detail
import com.verdenroz.corvid.feature.settings.resources.settings_name
import com.verdenroz.corvid.feature.settings.resources.settings_notices
import com.verdenroz.corvid.feature.settings.resources.settings_notices_detail
import com.verdenroz.corvid.feature.settings.resources.settings_notices_row
import com.verdenroz.corvid.feature.settings.resources.settings_security
import com.verdenroz.corvid.feature.settings.resources.settings_server
import com.verdenroz.corvid.feature.settings.resources.settings_sign_out
import com.verdenroz.corvid.feature.settings.resources.settings_sign_out_question
import com.verdenroz.corvid.feature.settings.resources.settings_theme
import com.verdenroz.corvid.feature.settings.resources.settings_theme_dark
import com.verdenroz.corvid.feature.settings.resources.settings_theme_light
import com.verdenroz.corvid.feature.settings.resources.settings_theme_system
import com.verdenroz.corvid.feature.settings.resources.settings_title
import org.jetbrains.compose.resources.stringResource

/** What the account section states; the server is always known, the other rows are left out while null. */
data class AccountFacts(
    val name: String?,
    val address: String?,
    val server: String,
    val identityProvider: String?,
    val manageSignInUrl: String? = null,
)

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    about: AboutViewModel,
    appVersion: String,
    account: AccountFacts,
    onBack: () -> Unit,
    onOpenFilters: () -> Unit,
    onOpenNotices: () -> Unit,
    onOpenApproveDevice: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val aboutState by about.state.collectAsState()
    val gutter = CorvidSpacing.gutter(LocalWideLayout.current)

    Column(modifier = Modifier.fillMaxSize().background(CorvidTheme.colors.surface)) {
        PageHeader(stringResource(Res.string.settings_title), stringResource(Res.string.settings_back), onBack)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = gutter).padding(bottom = 48.dp),
        ) {
            Column(Modifier.widthIn(max = 640.dp)) {
                AccountSection(account, state.confirmingSignOut, viewModel)
                SettingsSection(stringResource(Res.string.settings_appearance)) {
                    ChoiceRow(
                        title = stringResource(Res.string.settings_theme),
                        options = ThemeChoice.entries,
                        selected = state.settings.theme,
                        label = { stringResource(it.label()) },
                        onSelect = viewModel::selectTheme,
                    )
                }
                SettingsSection(stringResource(Res.string.settings_security)) {
                    ChoiceRow(
                        title = stringResource(Res.string.settings_auto_lock),
                        detail = stringResource(Res.string.settings_auto_lock_detail),
                        options = AutoLock.entries,
                        selected = state.settings.autoLock,
                        label = { stringResource(Res.string.settings_auto_lock_option, it.minutes) },
                        onSelect = viewModel::selectAutoLock,
                    )
                    Row(Modifier.padding(vertical = 14.dp)) {
                        PillButton(stringResource(Res.string.settings_lock_now), CorvidIcons.Lock, viewModel::lockNow)
                    }
                    LinkRow(
                        title = stringResource(Res.string.settings_approve_row),
                        detail = stringResource(Res.string.settings_approve_detail),
                        onClick = onOpenApproveDevice,
                    )
                }
                SettingsSection(stringResource(Res.string.settings_filters)) {
                    LinkRow(
                        title = stringResource(Res.string.settings_filters_row),
                        detail = stringResource(Res.string.settings_filters_detail),
                        onClick = onOpenFilters,
                    )
                }
                SettingsSection(stringResource(Res.string.settings_notices)) {
                    LinkRow(
                        title = stringResource(Res.string.settings_notices_row),
                        detail = stringResource(Res.string.settings_notices_detail),
                        onClick = onOpenNotices,
                    )
                }
                AboutSection(appVersion, aboutState, about)
            }
        }
    }
}

@Composable
private fun AccountSection(
    account: AccountFacts,
    confirmingSignOut: Boolean,
    viewModel: SettingsViewModel,
) {
    SettingsSection(stringResource(Res.string.settings_account)) {
        account.name?.let { FactRow(stringResource(Res.string.settings_name), it) }
        account.address?.let { FactRow(stringResource(Res.string.settings_address), it) }
        FactRow(stringResource(Res.string.settings_server), account.server)
        account.identityProvider?.let { FactRow(stringResource(Res.string.settings_identity_provider), it) }
        account.manageSignInUrl?.let {
            val uriHandler = LocalUriHandler.current
            LinkRow(
                title = stringResource(Res.string.settings_manage_sign_in),
                detail = stringResource(Res.string.settings_manage_sign_in_detail),
                onClick = { uriHandler.openUri(it) },
            )
        }
        if (confirmingSignOut) {
            DiscardQuestion(
                stringResource(Res.string.settings_sign_out_question),
                viewModel::confirmSignOut,
                viewModel::keepSignedIn,
                confirmLabel = stringResource(Res.string.settings_sign_out),
                cancelLabel = stringResource(Res.string.settings_cancel),
            )
        } else {
            Row(Modifier.padding(vertical = 14.dp)) {
                PillButton(stringResource(Res.string.settings_sign_out), null, viewModel::askSignOut)
            }
        }
    }
}

private fun ThemeChoice.label() =
    when (this) {
        ThemeChoice.System -> Res.string.settings_theme_system
        ThemeChoice.Light -> Res.string.settings_theme_light
        ThemeChoice.Dark -> Res.string.settings_theme_dark
    }
