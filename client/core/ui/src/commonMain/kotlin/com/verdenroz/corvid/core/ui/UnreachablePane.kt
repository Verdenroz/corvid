package com.verdenroz.corvid.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.verdenroz.corvid.core.designsystem.component.PillButton
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.core.ui.resources.Res
import com.verdenroz.corvid.core.ui.resources.try_again
import com.verdenroz.corvid.core.ui.resources.unreachable_detail
import com.verdenroz.corvid.core.ui.resources.unreachable_title
import org.jetbrains.compose.resources.stringResource

/**
 * Shown when the server or the device key could not be reached. Nothing is wiped: only a confirmed Missing lookup
 * (RevokedPane) means the device actually lost access. A server that never answers as hoped, or the wrong account,
 * leaves [onSignOut] as the way out.
 */
@Composable
fun UnreachablePane(
    reason: UiText,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StatePane(
        stringResource(Res.string.unreachable_title),
        modifier,
        detail = stringResource(Res.string.unreachable_detail, reason.asString()),
        onSignOut = onSignOut,
    ) {
        PillButton(stringResource(Res.string.try_again), null, onRetry)
    }
}
