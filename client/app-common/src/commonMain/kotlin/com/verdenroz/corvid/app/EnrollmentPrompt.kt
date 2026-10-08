package com.verdenroz.corvid.app

import androidx.compose.runtime.Composable
import com.verdenroz.corvid.appcommon.resources.Res
import com.verdenroz.corvid.appcommon.resources.enroll_detail
import com.verdenroz.corvid.appcommon.resources.enroll_title
import com.verdenroz.corvid.core.ui.StatePane
import org.jetbrains.compose.resources.stringResource

/** No key exists yet for [deviceName]: its first sign-in, or an operator revoke. */
@Composable
fun EnrollmentPrompt(deviceName: String) {
    StatePane(stringResource(Res.string.enroll_title, deviceName), detail = stringResource(Res.string.enroll_detail))
}
