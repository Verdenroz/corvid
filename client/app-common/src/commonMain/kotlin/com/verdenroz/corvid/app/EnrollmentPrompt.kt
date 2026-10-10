package com.verdenroz.corvid.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.verdenroz.corvid.app.shell.countdown
import com.verdenroz.corvid.app.shell.rememberRemaining
import com.verdenroz.corvid.appcommon.resources.Res
import com.verdenroz.corvid.appcommon.resources.enroll_ask_again
import com.verdenroz.corvid.appcommon.resources.enroll_code
import com.verdenroz.corvid.appcommon.resources.enroll_detail
import com.verdenroz.corvid.appcommon.resources.enroll_expired
import com.verdenroz.corvid.appcommon.resources.enroll_expires_in
import com.verdenroz.corvid.appcommon.resources.enroll_opening
import com.verdenroz.corvid.appcommon.resources.enroll_qr
import com.verdenroz.corvid.appcommon.resources.enroll_request_id
import com.verdenroz.corvid.appcommon.resources.enroll_self_approve
import com.verdenroz.corvid.appcommon.resources.enroll_self_approving
import com.verdenroz.corvid.appcommon.resources.enroll_title
import com.verdenroz.corvid.appcommon.resources.enroll_too_many
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.PillButton
import com.verdenroz.corvid.core.designsystem.component.PrimaryButton
import com.verdenroz.corvid.core.keyring.approvalLink
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.core.ui.StatePane
import org.jetbrains.compose.resources.stringResource

/**
 * The server holds no key for [deviceName]. This device shows what a master device needs to approve
 * it and, while visible, [onWatch] polls for the approval.
 */
@Composable
fun EnrollmentPrompt(
    deviceName: String,
    request: EnrollmentRequest,
    onAskAgain: () -> Unit,
    onApproveSelf: () -> Unit,
    onWatch: suspend () -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(request, lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { onWatch() } }

    StatePane(stringResource(Res.string.enroll_title, deviceName), detail = stringResource(Res.string.enroll_detail)) {
        when (request) {
            is EnrollmentRequest.Opening -> Text(stringResource(Res.string.enroll_opening), style = CorvidTheme.type.body)
            is EnrollmentRequest.Open -> OpenRequest(request, onApproveSelf)
            is EnrollmentRequest.Expired -> Ended(stringResource(Res.string.enroll_expired), onAskAgain)
            is EnrollmentRequest.TooManyOpen -> Ended(stringResource(Res.string.enroll_too_many), onAskAgain)
        }
    }
}

@Composable
private fun OpenRequest(
    open: EnrollmentRequest.Open,
    onApproveSelf: () -> Unit,
) {
    val colors = CorvidTheme.colors
    val request = open.request
    QrCode(request.approvalLink(), stringResource(Res.string.enroll_qr), Modifier.size(232.dp))
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(Res.string.enroll_request_id), style = CorvidTheme.type.meta, color = colors.inkMuted)
        Text(request.id, style = CorvidTheme.type.metaNumeric, color = colors.ink)
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(Res.string.enroll_code), style = CorvidTheme.type.meta, color = colors.inkMuted)
        Text(request.code, style = CorvidTheme.type.name, color = colors.ink)
    }
    rememberRemaining(request.expiresAt)?.let { remaining ->
        Text(stringResource(Res.string.enroll_expires_in, countdown(remaining)), style = CorvidTheme.type.meta, color = colors.inkMuted)
    }
    if (open.approving) {
        Text(stringResource(Res.string.enroll_self_approving), style = CorvidTheme.type.body)
    } else {
        PillButton(stringResource(Res.string.enroll_self_approve), icon = null, onClick = onApproveSelf)
    }
    open.failure?.let { Text(it.asString(), style = CorvidTheme.type.body, color = colors.ink) }
}

@Composable
private fun Ended(
    message: String,
    onAskAgain: () -> Unit,
) {
    Text(message, style = CorvidTheme.type.body)
    PrimaryButton(stringResource(Res.string.enroll_ask_again), onClick = onAskAgain)
}
