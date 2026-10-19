package com.verdenroz.corvid.app.shell

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.data.AppUpdateState
import com.verdenroz.corvid.core.designsystem.CorvidShape
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.ui.actionLabel
import com.verdenroz.corvid.core.ui.offerText
import com.verdenroz.corvid.core.ui.problemText
import org.jetbrains.compose.resources.stringResource

/** A banner above the shell offering a newer app build, following its download, or saying why the last attempt did not finish. */
@Composable
internal fun AppUpdateBannerBar(
    state: AppUpdateState,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val failed = state is AppUpdateState.Failed
    val problem = state.problemText()
    val headline = if (failed) state.offerText() else problem ?: state.offerText()
    ShellBannerBar(
        tone = if (failed) BannerTone.Danger else BannerTone.Accent,
        text =
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(headline.orEmpty()) }
                if (failed) append("  $problem")
            },
        onDismiss = onDismiss,
    ) { content ->
        val action = state.actionLabel() ?: return@ShellBannerBar
        TextButton(onClick = onUpdate, shape = CorvidShape.pill, border = BorderStroke(1.dp, content)) {
            Text(stringResource(action), style = CorvidTheme.type.meta, fontWeight = FontWeight.SemiBold, color = content)
        }
    }
}
