package com.verdenroz.corvid.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.auth.Passkey
import com.verdenroz.corvid.core.designsystem.CorvidSpacing
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.PillButton
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.core.ui.DiscardQuestion
import com.verdenroz.corvid.core.ui.LocalWideLayout
import com.verdenroz.corvid.core.ui.PageHeader
import com.verdenroz.corvid.feature.settings.resources.Res
import com.verdenroz.corvid.feature.settings.resources.passkeys_add_daily
import com.verdenroz.corvid.feature.settings.resources.passkeys_add_master
import com.verdenroz.corvid.feature.settings.resources.passkeys_added
import com.verdenroz.corvid.feature.settings.resources.passkeys_added_on
import com.verdenroz.corvid.feature.settings.resources.passkeys_cancel
import com.verdenroz.corvid.feature.settings.resources.passkeys_code_daily
import com.verdenroz.corvid.feature.settings.resources.passkeys_code_made
import com.verdenroz.corvid.feature.settings.resources.passkeys_code_master
import com.verdenroz.corvid.feature.settings.resources.passkeys_detail
import com.verdenroz.corvid.feature.settings.resources.passkeys_device_bound
import com.verdenroz.corvid.feature.settings.resources.passkeys_loading
import com.verdenroz.corvid.feature.settings.resources.passkeys_off
import com.verdenroz.corvid.feature.settings.resources.passkeys_refresh
import com.verdenroz.corvid.feature.settings.resources.passkeys_remove
import com.verdenroz.corvid.feature.settings.resources.passkeys_remove_question
import com.verdenroz.corvid.feature.settings.resources.passkeys_removed
import com.verdenroz.corvid.feature.settings.resources.passkeys_role_daily
import com.verdenroz.corvid.feature.settings.resources.passkeys_role_master
import com.verdenroz.corvid.feature.settings.resources.passkeys_signing_in
import com.verdenroz.corvid.feature.settings.resources.passkeys_synced
import com.verdenroz.corvid.feature.settings.resources.passkeys_title
import com.verdenroz.corvid.feature.settings.resources.passkeys_working
import com.verdenroz.corvid.feature.settings.resources.settings_back
import org.jetbrains.compose.resources.stringResource

@Composable
fun PasskeysScreen(
    viewModel: PasskeysViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val gutter = CorvidSpacing.gutter(LocalWideLayout.current)

    Column(modifier = Modifier.fillMaxSize().background(CorvidTheme.colors.surface)) {
        PageHeader(stringResource(Res.string.passkeys_title), stringResource(Res.string.settings_back), onBack)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = gutter).padding(top = 24.dp, bottom = 48.dp),
        ) {
            Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(Res.string.passkeys_detail), style = CorvidTheme.type.body, color = CorvidTheme.colors.inkMuted)
                Passkeys(state, viewModel)
                Actions(viewModel)
                StepText(state.step)
            }
        }
    }
}

@Composable
private fun Passkeys(
    state: PasskeysState,
    viewModel: PasskeysViewModel,
) {
    when (val list = state.list) {
        PasskeyList.Loading -> Text(stringResource(Res.string.passkeys_loading), style = CorvidTheme.type.body)
        PasskeyList.Off -> Text(stringResource(Res.string.passkeys_off), style = CorvidTheme.type.body)
        is PasskeyList.Failed -> {
            Text(list.message.asString(), style = CorvidTheme.type.body)
            PillButton(stringResource(Res.string.passkeys_refresh), null, onClick = viewModel::refresh)
        }
        is PasskeyList.Listed ->
            Column {
                HorizontalDivider(color = CorvidTheme.colors.line)
                list.passkeys.forEach { passkey ->
                    PasskeyRow(passkey, state.removing == passkey.id, viewModel)
                    HorizontalDivider(color = CorvidTheme.colors.line)
                }
            }
    }
}

@Composable
private fun PasskeyRow(
    passkey: Passkey,
    confirming: Boolean,
    viewModel: PasskeysViewModel,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(passkey.label()), style = CorvidTheme.type.name)
                val kind = if (passkey.backupEligible) Res.string.passkeys_synced else Res.string.passkeys_device_bound
                val added = stringResource(Res.string.passkeys_added_on, passkey.createdAt.toString().substringBefore('T'))
                Text(
                    stringResource(kind) + " · " + added,
                    style = CorvidTheme.type.meta,
                    color = CorvidTheme.colors.inkMuted,
                )
            }
            if (!confirming) {
                PillButton(stringResource(Res.string.passkeys_remove), null, onClick = { viewModel.askRemove(passkey.id) })
            }
        }
        if (confirming) {
            DiscardQuestion(
                stringResource(Res.string.passkeys_remove_question),
                { viewModel.remove(passkey.id) },
                viewModel::keep,
                confirmLabel = stringResource(Res.string.passkeys_remove),
                cancelLabel = stringResource(Res.string.passkeys_cancel),
            )
        }
    }
}

@Composable
private fun Actions(viewModel: PasskeysViewModel) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PillButton(stringResource(Res.string.passkeys_add_daily), null, onClick = { viewModel.addHere(false) })
        PillButton(stringResource(Res.string.passkeys_add_master), null, onClick = { viewModel.addHere(true) })
        PillButton(stringResource(Res.string.passkeys_code_daily), null, onClick = { viewModel.makeCode(false) })
        PillButton(stringResource(Res.string.passkeys_code_master), null, onClick = { viewModel.makeCode(true) })
    }
}

@Composable
private fun StepText(step: PasskeyStep) {
    val text =
        when (step) {
            PasskeyStep.Idle -> return
            PasskeyStep.SigningIn -> stringResource(Res.string.passkeys_signing_in)
            PasskeyStep.Working -> stringResource(Res.string.passkeys_working)
            is PasskeyStep.Code -> stringResource(Res.string.passkeys_code_made, stringResource(approvesLabel(step.approves)), step.code)
            is PasskeyStep.Added -> stringResource(Res.string.passkeys_added, stringResource(approvesLabel(step.approves)))
            PasskeyStep.Removed -> stringResource(Res.string.passkeys_removed)
            is PasskeyStep.Failed -> step.message.asString()
        }
    Text(text, style = CorvidTheme.type.body, color = CorvidTheme.colors.ink)
}

private fun Passkey.label() = approvesLabel(approves)

private fun approvesLabel(approves: Boolean) = if (approves) Res.string.passkeys_role_master else Res.string.passkeys_role_daily
