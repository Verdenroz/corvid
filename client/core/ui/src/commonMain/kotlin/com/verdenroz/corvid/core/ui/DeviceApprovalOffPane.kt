package com.verdenroz.corvid.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.verdenroz.corvid.core.designsystem.component.PillButton
import com.verdenroz.corvid.core.ui.resources.Res
import com.verdenroz.corvid.core.ui.resources.device_approval_off_ask_again
import com.verdenroz.corvid.core.ui.resources.device_approval_off_detail
import com.verdenroz.corvid.core.ui.resources.device_approval_off_title
import org.jetbrains.compose.resources.stringResource

/**
 * Shown when the sentinel answers that device approval is switched off. Signing out is the way
 * to another account or server, since this one hands out no key until its administrator acts.
 */
@Composable
fun DeviceApprovalOffPane(
    onAskAgain: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StatePane(
        stringResource(Res.string.device_approval_off_title),
        modifier,
        detail = stringResource(Res.string.device_approval_off_detail),
        onSignOut = onSignOut,
    ) {
        PillButton(stringResource(Res.string.device_approval_off_ask_again), null, onAskAgain)
    }
}
