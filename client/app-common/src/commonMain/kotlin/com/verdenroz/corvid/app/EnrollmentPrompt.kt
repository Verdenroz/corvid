package com.verdenroz.corvid.app

import androidx.compose.runtime.Composable
import com.verdenroz.corvid.appcommon.resources.Res
import com.verdenroz.corvid.appcommon.resources.enroll_confirm
import com.verdenroz.corvid.appcommon.resources.enroll_detail
import com.verdenroz.corvid.appcommon.resources.enroll_title
import com.verdenroz.corvid.core.designsystem.component.PrimaryButton
import com.verdenroz.corvid.core.ui.StatePane
import org.jetbrains.compose.resources.stringResource

/**
 * No key exists yet for [deviceName]: could be its first sign-in, or an
 * operator revoke. Either way, enrolling means this device gets new access,
 * so it needs its own tap rather than following straight from naming the
 * device.
 */
@Composable
fun EnrollmentPrompt(
    deviceName: String,
    onConfirm: () -> Unit,
) {
    StatePane(stringResource(Res.string.enroll_title, deviceName), detail = stringResource(Res.string.enroll_detail)) {
        PrimaryButton(stringResource(Res.string.enroll_confirm, deviceName), onClick = onConfirm)
    }
}
