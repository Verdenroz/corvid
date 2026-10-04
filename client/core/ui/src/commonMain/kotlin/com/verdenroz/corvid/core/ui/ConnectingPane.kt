package com.verdenroz.corvid.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.ui.resources.Res
import com.verdenroz.corvid.core.ui.resources.connecting_title
import org.jetbrains.compose.resources.stringResource

/** A statement, not a spinner: there is no progress to report until the server answers. */
@Composable
fun ConnectingPane(modifier: Modifier = Modifier) {
    StatePane(stringResource(Res.string.connecting_title), modifier, headlineColor = CorvidTheme.colors.inkMuted)
}
