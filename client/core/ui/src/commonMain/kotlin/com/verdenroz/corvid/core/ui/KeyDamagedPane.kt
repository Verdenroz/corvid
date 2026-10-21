package com.verdenroz.corvid.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.verdenroz.corvid.core.designsystem.component.PillButton
import com.verdenroz.corvid.core.ui.resources.Res
import com.verdenroz.corvid.core.ui.resources.key_damaged_detail
import com.verdenroz.corvid.core.ui.resources.key_damaged_title
import com.verdenroz.corvid.core.ui.resources.try_again
import org.jetbrains.compose.resources.stringResource

/** The server answered with a key record or blob this app cannot read; nothing is wiped and the server was reached. */
@Composable
fun KeyDamagedPane(
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StatePane(
        stringResource(Res.string.key_damaged_title),
        modifier,
        detail = stringResource(Res.string.key_damaged_detail),
        onSignOut = onSignOut,
    ) {
        PillButton(stringResource(Res.string.try_again), null, onRetry)
    }
}
