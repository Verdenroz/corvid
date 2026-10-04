package com.verdenroz.corvid.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.ui.resources.Res
import com.verdenroz.corvid.core.ui.resources.revoked_detail
import com.verdenroz.corvid.core.ui.resources.revoked_title
import org.jetbrains.compose.resources.stringResource

/**
 * Shown when a query that found nothing follows a key this session already
 * held: the file is confirmed gone, not just unreachable, so there is
 * nothing to retry. Reaching this device's access again means enrolling it
 * again from a state that still has access.
 */
@Composable
fun RevokedPane(modifier: Modifier = Modifier) {
    StatePane(
        stringResource(Res.string.revoked_title),
        modifier,
        detail = stringResource(Res.string.revoked_detail),
        headlineColor = CorvidTheme.colors.danger,
    )
}
