package com.verdenroz.corvid.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.designsystem.CorvidSpacing
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.CorvidTextField
import com.verdenroz.corvid.core.designsystem.component.PillButton
import com.verdenroz.corvid.core.designsystem.component.PrimaryButton
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.core.ui.LocalWideLayout
import com.verdenroz.corvid.core.ui.PageHeader
import com.verdenroz.corvid.feature.settings.resources.Res
import com.verdenroz.corvid.feature.settings.resources.approve_action
import com.verdenroz.corvid.feature.settings.resources.approve_approved
import com.verdenroz.corvid.feature.settings.resources.approve_code
import com.verdenroz.corvid.feature.settings.resources.approve_detail
import com.verdenroz.corvid.feature.settings.resources.approve_id
import com.verdenroz.corvid.feature.settings.resources.approve_scan
import com.verdenroz.corvid.feature.settings.resources.approve_sending
import com.verdenroz.corvid.feature.settings.resources.approve_signing_in
import com.verdenroz.corvid.feature.settings.resources.approve_title
import com.verdenroz.corvid.feature.settings.resources.settings_back
import org.jetbrains.compose.resources.stringResource

@Composable
fun ApproveDeviceScreen(
    viewModel: ApproveDeviceViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val scan = rememberQrScanLauncher(viewModel::scanned)
    val gutter = CorvidSpacing.gutter(LocalWideLayout.current)

    Column(modifier = Modifier.fillMaxSize().background(CorvidTheme.colors.surface)) {
        PageHeader(stringResource(Res.string.approve_title), stringResource(Res.string.settings_back), onBack)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = gutter).padding(top = 24.dp, bottom = 48.dp),
        ) {
            Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(Res.string.approve_detail), style = CorvidTheme.type.body, color = CorvidTheme.colors.inkMuted)
                scan?.let { PillButton(stringResource(Res.string.approve_scan), null, onClick = it) }
                CorvidTextField(state.id, viewModel::setId, stringResource(Res.string.approve_id), Modifier.fillMaxWidth())
                CorvidTextField(
                    state.code,
                    viewModel::setCode,
                    stringResource(Res.string.approve_code),
                    Modifier.fillMaxWidth(),
                    KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                PrimaryButton(stringResource(Res.string.approve_action), viewModel::approve, enabled = state.canApprove)
                StepText(state.step)
            }
        }
    }
}

@Composable
private fun StepText(step: ApproveStep) {
    val text =
        when (step) {
            is ApproveStep.Editing -> return
            is ApproveStep.SigningIn -> stringResource(Res.string.approve_signing_in)
            is ApproveStep.Sending -> stringResource(Res.string.approve_sending)
            is ApproveStep.Approved -> stringResource(Res.string.approve_approved, step.shortId)
            is ApproveStep.Failed -> step.message.asString()
        }
    Text(text, style = CorvidTheme.type.body, color = CorvidTheme.colors.ink)
}
