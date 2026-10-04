package com.verdenroz.corvid.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.designsystem.CorvidShape
import com.verdenroz.corvid.core.designsystem.CorvidSpacing
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.CorvidIcons
import com.verdenroz.corvid.core.designsystem.component.PillButton
import com.verdenroz.corvid.core.prefs.AutoLock
import com.verdenroz.corvid.core.prefs.ThemeChoice
import com.verdenroz.corvid.core.ui.LocalWideLayout
import com.verdenroz.corvid.feature.settings.resources.Res
import com.verdenroz.corvid.feature.settings.resources.settings_account
import com.verdenroz.corvid.feature.settings.resources.settings_address
import com.verdenroz.corvid.feature.settings.resources.settings_appearance
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
import com.verdenroz.corvid.feature.settings.resources.settings_name
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

/** What the account section states; each is null until known, and a null row is left out. */
data class AccountFacts(
    val name: String?,
    val address: String?,
    val server: String,
    val identityProvider: String?,
)

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    account: AccountFacts,
    onBack: () -> Unit,
    onOpenFilters: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val gutter = CorvidSpacing.gutter(LocalWideLayout.current)

    Column(modifier = Modifier.fillMaxSize().background(CorvidTheme.colors.surface)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, top = 4.dp, end = 16.dp)) {
            IconButton(onClick = onBack) {
                Icon(CorvidIcons.Back, stringResource(Res.string.settings_back), tint = CorvidTheme.colors.inkMuted)
            }
            Text(stringResource(Res.string.settings_title), style = CorvidTheme.type.headline)
        }
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
                }
                SettingsSection(stringResource(Res.string.settings_filters)) {
                    LinkRow(
                        title = stringResource(Res.string.settings_filters_row),
                        detail = stringResource(Res.string.settings_filters_detail),
                        onClick = onOpenFilters,
                    )
                }
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
        if (confirmingSignOut) {
            SignOutQuestion(onConfirm = viewModel::confirmSignOut, onCancel = viewModel::keepSignedIn)
        } else {
            Row(Modifier.padding(vertical = 14.dp)) {
                PillButton(stringResource(Res.string.settings_sign_out), null, viewModel::askSignOut)
            }
        }
    }
}

@Composable
private fun SignOutQuestion(
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = CorvidTheme.colors
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().background(colors.dangerSoft, CorvidShape.medium).padding(16.dp),
    ) {
        Text(stringResource(Res.string.settings_sign_out_question), style = CorvidTheme.type.metaStrong, color = colors.onDangerSoft)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(stringResource(Res.string.settings_sign_out), null, onConfirm, height = 30.dp)
            PillButton(stringResource(Res.string.settings_cancel), null, onCancel, height = 30.dp)
        }
    }
}

private fun ThemeChoice.label() =
    when (this) {
        ThemeChoice.System -> Res.string.settings_theme_system
        ThemeChoice.Light -> Res.string.settings_theme_light
        ThemeChoice.Dark -> Res.string.settings_theme_dark
    }
