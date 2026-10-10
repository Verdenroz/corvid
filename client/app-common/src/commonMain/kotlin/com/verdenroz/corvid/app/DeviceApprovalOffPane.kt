package com.verdenroz.corvid.app

import androidx.compose.runtime.Composable
import com.verdenroz.corvid.appcommon.resources.Res
import com.verdenroz.corvid.appcommon.resources.device_approval_off_detail
import com.verdenroz.corvid.appcommon.resources.device_approval_off_title
import com.verdenroz.corvid.appcommon.resources.enroll_ask_again
import com.verdenroz.corvid.core.designsystem.component.PillButton
import com.verdenroz.corvid.core.ui.StatePane
import org.jetbrains.compose.resources.stringResource

@Composable
fun DeviceApprovalOffPane(onAskAgain: () -> Unit) {
    StatePane(stringResource(Res.string.device_approval_off_title), detail = stringResource(Res.string.device_approval_off_detail)) {
        PillButton(stringResource(Res.string.enroll_ask_again), icon = null, onClick = onAskAgain)
    }
}
