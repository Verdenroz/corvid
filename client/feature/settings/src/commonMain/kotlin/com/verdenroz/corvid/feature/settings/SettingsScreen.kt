package com.verdenroz.corvid.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.data.AppUpdateState
import com.verdenroz.corvid.core.designsystem.CorvidSpacing
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.LocalWideLayout
import com.verdenroz.corvid.core.designsystem.component.CorvidIcons
import com.verdenroz.corvid.core.designsystem.component.PillButton
import com.verdenroz.corvid.core.prefs.AutoLock
import com.verdenroz.corvid.core.prefs.PushStatus
import com.verdenroz.corvid.core.prefs.ThemeChoice
import com.verdenroz.corvid.core.ui.DiscardQuestion
import com.verdenroz.corvid.core.ui.PageHeader
import com.verdenroz.corvid.feature.settings.resources.Res
import com.verdenroz.corvid.feature.settings.resources.settings_about_off
import com.verdenroz.corvid.feature.settings.resources.settings_about_on
import com.verdenroz.corvid.feature.settings.resources.settings_account
import com.verdenroz.corvid.feature.settings.resources.settings_address
import com.verdenroz.corvid.feature.settings.resources.settings_approve_detail
import com.verdenroz.corvid.feature.settings.resources.settings_approve_row
import com.verdenroz.corvid.feature.settings.resources.settings_auto_lock
import com.verdenroz.corvid.feature.settings.resources.settings_auto_lock_detail
import com.verdenroz.corvid.feature.settings.resources.settings_auto_lock_option
import com.verdenroz.corvid.feature.settings.resources.settings_back
import com.verdenroz.corvid.feature.settings.resources.settings_cancel
import com.verdenroz.corvid.feature.settings.resources.settings_digest_detail
import com.verdenroz.corvid.feature.settings.resources.settings_digest_row
import com.verdenroz.corvid.feature.settings.resources.settings_filters_detail
import com.verdenroz.corvid.feature.settings.resources.settings_filters_row
import com.verdenroz.corvid.feature.settings.resources.settings_lock_now
import com.verdenroz.corvid.feature.settings.resources.settings_name
import com.verdenroz.corvid.feature.settings.resources.settings_new_mail
import com.verdenroz.corvid.feature.settings.resources.settings_new_mail_detail
import com.verdenroz.corvid.feature.settings.resources.settings_notices_detail
import com.verdenroz.corvid.feature.settings.resources.settings_notices_row
import com.verdenroz.corvid.feature.settings.resources.settings_passkeys_detail
import com.verdenroz.corvid.feature.settings.resources.settings_passkeys_row
import com.verdenroz.corvid.feature.settings.resources.settings_push
import com.verdenroz.corvid.feature.settings.resources.settings_push_failed
import com.verdenroz.corvid.feature.settings.resources.settings_push_google_unavailable
import com.verdenroz.corvid.feature.settings.resources.settings_push_not_approved
import com.verdenroz.corvid.feature.settings.resources.settings_push_off
import com.verdenroz.corvid.feature.settings.resources.settings_push_off_own_connection
import com.verdenroz.corvid.feature.settings.resources.settings_push_refused
import com.verdenroz.corvid.feature.settings.resources.settings_push_registering
import com.verdenroz.corvid.feature.settings.resources.settings_push_removing
import com.verdenroz.corvid.feature.settings.resources.settings_push_sent
import com.verdenroz.corvid.feature.settings.resources.settings_push_sent_own_connection
import com.verdenroz.corvid.feature.settings.resources.settings_push_server_off
import com.verdenroz.corvid.feature.settings.resources.settings_push_waiting
import com.verdenroz.corvid.feature.settings.resources.settings_server
import com.verdenroz.corvid.feature.settings.resources.settings_sign_out
import com.verdenroz.corvid.feature.settings.resources.settings_sign_out_question
import com.verdenroz.corvid.feature.settings.resources.settings_theme
import com.verdenroz.corvid.feature.settings.resources.settings_theme_dark
import com.verdenroz.corvid.feature.settings.resources.settings_theme_light
import com.verdenroz.corvid.feature.settings.resources.settings_theme_system
import com.verdenroz.corvid.feature.settings.resources.settings_title
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.stringResource

/** What the account section states; the server is always known, the other rows are left out while null. */
data class AccountFacts(
    val name: String?,
    val address: String?,
    val server: String,
)

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    about: AboutViewModel,
    appVersion: String,
    appUpdate: StateFlow<AppUpdateState>,
    onUpdateApp: () -> Unit,
    account: AccountFacts,
    onBack: () -> Unit,
    onOpenFilters: () -> Unit,
    onOpenNotices: () -> Unit,
    onOpenDigest: (() -> Unit)?,
    onOpenApproveDevice: () -> Unit,
    onOpenPasskeys: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val aboutState by about.state.collectAsState()
    val wide = LocalWideLayout.current
    val gutter = CorvidSpacing.gutter(wide)
    var current by rememberSaveable { mutableStateOf(SettingsTopic.Account) }

    val topic: @Composable (SettingsTopic) -> Unit = {
        when (it) {
            SettingsTopic.Account -> AccountSection(account, state.confirmingSignOut, viewModel, onOpenPasskeys)
            SettingsTopic.Appearance ->
                SettingsSection(stringResource(it.title)) {
                    ChoiceRow(
                        title = stringResource(Res.string.settings_theme),
                        options = ThemeChoice.entries,
                        selected = state.settings.theme,
                        label = { choice -> stringResource(choice.label()) },
                        onSelect = viewModel::selectTheme,
                    )
                }
            SettingsTopic.Security ->
                SettingsSection(stringResource(it.title)) {
                    ChoiceRow(
                        title = stringResource(Res.string.settings_auto_lock),
                        detail = stringResource(Res.string.settings_auto_lock_detail),
                        options = AutoLock.entries,
                        selected = state.settings.autoLock,
                        label = { choice -> stringResource(Res.string.settings_auto_lock_option, choice.minutes) },
                        onSelect = viewModel::selectAutoLock,
                    )
                    Row(Modifier.padding(vertical = 14.dp)) {
                        PillButton(stringResource(Res.string.settings_lock_now), CorvidIcons.Lock, viewModel::lockNow)
                    }
                }
            SettingsTopic.Devices ->
                SettingsSection(stringResource(it.title)) {
                    LinkRow(
                        title = stringResource(Res.string.settings_approve_row),
                        detail = stringResource(Res.string.settings_approve_detail),
                        onClick = onOpenApproveDevice,
                    )
                }
            SettingsTopic.Filters ->
                SettingsSection(stringResource(it.title)) {
                    LinkRow(
                        title = stringResource(Res.string.settings_filters_row),
                        detail = stringResource(Res.string.settings_filters_detail),
                        onClick = onOpenFilters,
                    )
                }
            SettingsTopic.Notices ->
                SettingsSection(stringResource(it.title)) {
                    ChoiceRow(
                        title = stringResource(Res.string.settings_new_mail),
                        detail = stringResource(Res.string.settings_new_mail_detail),
                        options = listOf(true, false),
                        selected = state.settings.newMailNotifications,
                        label = { on -> stringResource(if (on) Res.string.settings_about_on else Res.string.settings_about_off) },
                        onSelect = viewModel::setNewMailNotifications,
                    )
                    state.backgroundPush?.let { row ->
                        ChoiceRow(
                            title = stringResource(Res.string.settings_push),
                            detail = stringResource(row.text()),
                            options = listOf(true, false),
                            selected = row.on,
                            label = { on -> stringResource(if (on) Res.string.settings_about_on else Res.string.settings_about_off) },
                            onSelect = viewModel::setBackgroundPush,
                        )
                    }
                    LinkRow(
                        title = stringResource(Res.string.settings_notices_row),
                        detail = stringResource(Res.string.settings_notices_detail),
                        onClick = onOpenNotices,
                    )
                    onOpenDigest?.let {
                        LinkRow(
                            title = stringResource(Res.string.settings_digest_row),
                            detail = stringResource(Res.string.settings_digest_detail),
                            onClick = it,
                        )
                    }
                }
            SettingsTopic.About -> AboutSection(appVersion, appUpdate, onUpdateApp, aboutState, about)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(CorvidTheme.colors.surface)) {
        PageHeader(stringResource(Res.string.settings_title), stringResource(Res.string.settings_back), onBack)
        Row(Modifier.fillMaxSize()) {
            if (wide) TopicList(current) { current = it }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = gutter).padding(bottom = 48.dp),
            ) {
                Column(Modifier.widthIn(max = 640.dp)) {
                    if (wide) topic(current) else SettingsTopic.entries.forEach { topic(it) }
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
    onOpenPasskeys: () -> Unit,
) {
    SettingsSection(stringResource(Res.string.settings_account)) {
        account.name?.let { FactRow(stringResource(Res.string.settings_name), it) }
        account.address?.let { FactRow(stringResource(Res.string.settings_address), it) }
        FactRow(stringResource(Res.string.settings_server), account.server)
        LinkRow(
            title = stringResource(Res.string.settings_passkeys_row),
            detail = stringResource(Res.string.settings_passkeys_detail),
            onClick = onOpenPasskeys,
        )
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

private fun BackgroundPushState.text() =
    when (status) {
        PushStatus.Off -> if (ownConnection) Res.string.settings_push_off_own_connection else Res.string.settings_push_off
        PushStatus.Registering -> Res.string.settings_push_registering
        PushStatus.Unsent -> Res.string.settings_push_waiting
        PushStatus.Sent -> if (ownConnection) Res.string.settings_push_sent_own_connection else Res.string.settings_push_sent
        PushStatus.OffOnServer -> Res.string.settings_push_server_off
        PushStatus.NotApproved -> Res.string.settings_push_not_approved
        PushStatus.Refused -> Res.string.settings_push_refused
        PushStatus.Removing -> Res.string.settings_push_removing
        PushStatus.Failed -> Res.string.settings_push_failed
        PushStatus.GoogleUnavailable -> Res.string.settings_push_google_unavailable
    }
