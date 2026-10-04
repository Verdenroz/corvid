package com.verdenroz.corvid.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.verdenroz.corvid.core.designsystem.component.PillButton
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.core.ui.resources.Res
import com.verdenroz.corvid.core.ui.resources.unreachable_detail
import com.verdenroz.corvid.core.ui.resources.unreachable_retry
import com.verdenroz.corvid.core.ui.resources.unreachable_title
import org.jetbrains.compose.resources.stringResource

/**
 * Shown for a network failure, a damaged key file or an ambiguous device
 * folder set. Nothing is wiped for any of the three: only a confirmed
 * Missing lookup (RevokedPane) means the device actually lost access.
 */
@Composable
fun UnreachablePane(
    reason: UiText,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StatePane(
        stringResource(Res.string.unreachable_title),
        modifier,
        detail = stringResource(Res.string.unreachable_detail, reason.asString()),
    ) {
        PillButton(stringResource(Res.string.unreachable_retry), null, onRetry)
    }
}
