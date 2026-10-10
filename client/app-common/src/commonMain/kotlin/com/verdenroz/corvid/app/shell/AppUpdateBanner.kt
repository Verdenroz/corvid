package com.verdenroz.corvid.app.shell

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.verdenroz.corvid.core.data.AppUpdateState
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.ui.actionLabel
import com.verdenroz.corvid.core.ui.offerText
import com.verdenroz.corvid.core.ui.problemText
import org.jetbrains.compose.resources.stringResource

/** One line above the shell offering a newer app build, or saying why the last attempt did not finish. */
@Composable
internal fun AppUpdateBannerBar(
    state: AppUpdateState,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val line = state.problemText() ?: state.offerText().orEmpty()
    ShellBannerBar(
        tone = if (state is AppUpdateState.Failed) BannerTone.Danger else BannerTone.Accent,
        text = buildAnnotatedString { withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(line) } },
        onDismiss = onDismiss,
    ) { content ->
        TextButton(onClick = onUpdate) {
            Text(state.actionLabel()?.let { stringResource(it) }.orEmpty(), style = CorvidTheme.type.meta, color = content)
        }
    }
}
