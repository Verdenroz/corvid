package com.verdenroz.corvid.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.auth.Passkey
import com.verdenroz.corvid.core.designsystem.CorvidShape
import com.verdenroz.corvid.core.designsystem.CorvidSpacing
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.LocalWideLayout
import com.verdenroz.corvid.core.designsystem.component.PillButton
import com.verdenroz.corvid.core.designsystem.component.QuietLink
import com.verdenroz.corvid.core.designsystem.focusRingInset
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.core.ui.DiscardQuestion
import com.verdenroz.corvid.core.ui.PageHeader
import com.verdenroz.corvid.core.ui.StatusLine
import com.verdenroz.corvid.feature.settings.resources.Res
import com.verdenroz.corvid.feature.settings.resources.passkeys_add_heading
import com.verdenroz.corvid.feature.settings.resources.passkeys_add_here
import com.verdenroz.corvid.feature.settings.resources.passkeys_add_here_asks
import com.verdenroz.corvid.feature.settings.resources.passkeys_add_other
import com.verdenroz.corvid.feature.settings.resources.passkeys_add_other_asks
import com.verdenroz.corvid.feature.settings.resources.passkeys_added
import com.verdenroz.corvid.feature.settings.resources.passkeys_added_approver
import com.verdenroz.corvid.feature.settings.resources.passkeys_added_on
import com.verdenroz.corvid.feature.settings.resources.passkeys_approves
import com.verdenroz.corvid.feature.settings.resources.passkeys_can_approve
import com.verdenroz.corvid.feature.settings.resources.passkeys_cancel
import com.verdenroz.corvid.feature.settings.resources.passkeys_code_approves
import com.verdenroz.corvid.feature.settings.resources.passkeys_code_expiry
import com.verdenroz.corvid.feature.settings.resources.passkeys_code_plain
import com.verdenroz.corvid.feature.settings.resources.passkeys_detail
import com.verdenroz.corvid.feature.settings.resources.passkeys_device_bound
import com.verdenroz.corvid.feature.settings.resources.passkeys_loading
import com.verdenroz.corvid.feature.settings.resources.passkeys_off
import com.verdenroz.corvid.feature.settings.resources.passkeys_refresh
import com.verdenroz.corvid.feature.settings.resources.passkeys_remove
import com.verdenroz.corvid.feature.settings.resources.passkeys_remove_question
import com.verdenroz.corvid.feature.settings.resources.passkeys_removed
import com.verdenroz.corvid.feature.settings.resources.passkeys_row
import com.verdenroz.corvid.feature.settings.resources.passkeys_signing_in
import com.verdenroz.corvid.feature.settings.resources.passkeys_synced
import com.verdenroz.corvid.feature.settings.resources.passkeys_title
import com.verdenroz.corvid.feature.settings.resources.passkeys_working
import com.verdenroz.corvid.feature.settings.resources.settings_back
import org.jetbrains.compose.resources.StringResource
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
                if (state.list is PasskeyList.Listed) {
                    Actions(state, viewModel)
                    StepText(state.step)
                }
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
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val added = stringResource(Res.string.passkeys_added_on, passkey.createdAt.toString().substringBefore('T'))
                    Text(stringResource(Res.string.passkeys_row, added), style = CorvidTheme.type.name)
                    if (passkey.approves) ApprovesTag()
                }
                val kind = if (passkey.backupEligible) Res.string.passkeys_synced else Res.string.passkeys_device_bound
                Text(stringResource(kind), style = CorvidTheme.type.meta, color = CorvidTheme.colors.inkMuted)
            }
            if (!confirming) {
                QuietLink(stringResource(Res.string.passkeys_remove), onClick = { viewModel.askRemove(passkey.id) })
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
private fun ApprovesTag() {
    Text(
        stringResource(Res.string.passkeys_approves),
        style = CorvidTheme.type.chip,
        color = CorvidTheme.colors.inkMuted,
        modifier = Modifier.border(1.dp, CorvidTheme.colors.line, CorvidShape.pill).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun Actions(
    state: PasskeysState,
    viewModel: PasskeysViewModel,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(Res.string.passkeys_add_heading),
            style = CorvidTheme.type.dayHeader,
            color = CorvidTheme.colors.ink,
            modifier = Modifier.semantics { heading() },
        )
        Row(
            Modifier.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Switch(checked = state.newApproves, onCheckedChange = viewModel::setNewApproves, enabled = !state.busy)
            Text(stringResource(Res.string.passkeys_can_approve), style = CorvidTheme.type.name)
        }
        HorizontalDivider(color = CorvidTheme.colors.line)
        ActionRow(Res.string.passkeys_add_here, Res.string.passkeys_add_here_asks, !state.busy) { viewModel.addHere() }
        HorizontalDivider(color = CorvidTheme.colors.line)
        ActionRow(Res.string.passkeys_add_other, Res.string.passkeys_add_other_asks, !state.busy) { viewModel.makeCode() }
        HorizontalDivider(color = CorvidTheme.colors.line)
    }
}

@Composable
private fun ActionRow(
    title: StringResource,
    asks: StringResource,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .focusRingInset()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(stringResource(title), style = CorvidTheme.type.name, fontWeight = FontWeight.SemiBold, color = CorvidTheme.colors.ink)
        Text(stringResource(asks), style = CorvidTheme.type.meta, color = CorvidTheme.colors.inkMuted)
    }
}

@Composable
private fun StepText(step: PasskeyStep) {
    when (step) {
        PasskeyStep.Idle -> Unit
        is PasskeyStep.Code -> IssuedCode(step)
        else -> {
            val text =
                when (step) {
                    PasskeyStep.Idle, is PasskeyStep.Code -> return
                    PasskeyStep.SigningIn -> stringResource(Res.string.passkeys_signing_in)
                    PasskeyStep.Working -> stringResource(Res.string.passkeys_working)
                    is PasskeyStep.Added ->
                        stringResource(
                            if (step.approves) Res.string.passkeys_added_approver else Res.string.passkeys_added,
                        )
                    PasskeyStep.Removed -> stringResource(Res.string.passkeys_removed)
                    is PasskeyStep.Failed -> step.message.asString()
                }
            StatusLine(text, failed = step is PasskeyStep.Failed)
        }
    }
}

@Composable
private fun IssuedCode(step: PasskeyStep.Code) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(step.code, style = CorvidTheme.type.headline, color = CorvidTheme.colors.ink)
        val approves = stringResource(if (step.approves) Res.string.passkeys_code_approves else Res.string.passkeys_code_plain)
        Text(stringResource(Res.string.passkeys_code_expiry, approves), style = CorvidTheme.type.meta, color = CorvidTheme.colors.inkMuted)
    }
}
