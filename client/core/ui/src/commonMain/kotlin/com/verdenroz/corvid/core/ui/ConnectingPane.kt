package com.verdenroz.corvid.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.ui.resources.Res
import com.verdenroz.corvid.core.ui.resources.connecting_title
import org.jetbrains.compose.resources.stringResource

@Composable
fun ConnectingPane(modifier: Modifier = Modifier) {
    StatePane(stringResource(Res.string.connecting_title), modifier, headlineColor = CorvidTheme.colors.inkMuted)
}
