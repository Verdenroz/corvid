package com.verdenroz.corvid.feature.outbox

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.ui.PageHeader
import com.verdenroz.corvid.core.ui.QuietPane
import com.verdenroz.corvid.feature.outbox.resources.Res
import com.verdenroz.corvid.feature.outbox.resources.outbox_back
import com.verdenroz.corvid.feature.outbox.resources.outbox_failed
import com.verdenroz.corvid.feature.outbox.resources.outbox_held
import com.verdenroz.corvid.feature.outbox.resources.outbox_title
import org.jetbrains.compose.resources.stringResource

/** The outbox page for an unlock that has no outbox; [held] when another window has it, else it could not be opened. */
@Composable
fun OutboxUnavailableScreen(
    held: Boolean,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(CorvidTheme.colors.surface)) {
        PageHeader(stringResource(Res.string.outbox_title), stringResource(Res.string.outbox_back), onBack)
        QuietPane(stringResource(if (held) Res.string.outbox_held else Res.string.outbox_failed))
    }
}
