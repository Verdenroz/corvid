package com.verdenroz.corvid.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.appcommon.resources.Res
import com.verdenroz.corvid.appcommon.resources.notices_dismiss
import com.verdenroz.corvid.core.designsystem.CorvidSpacing
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.LocalWideLayout
import com.verdenroz.corvid.core.designsystem.component.CorvidIcons
import org.jetbrains.compose.resources.stringResource

internal enum class BannerTone { Accent, Danger }

/** The strip above the shell: up to two lines of [text] on a [tone] background, [trailing] before the close button. */
@Composable
internal fun ShellBannerBar(
    tone: BannerTone,
    text: AnnotatedString,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.(Color) -> Unit = {},
) {
    val colors = CorvidTheme.colors
    val background = if (tone == BannerTone.Danger) colors.dangerSoft else colors.accentSoft
    val content = if (tone == BannerTone.Danger) colors.onDangerSoft else colors.onAccentSoft
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(background)
                .then(modifier)
                .heightIn(min = 44.dp)
                .padding(start = CorvidSpacing.gutter(LocalWideLayout.current)),
    ) {
        Text(
            text,
            style = CorvidTheme.type.meta,
            color = content,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
        trailing(content)
        IconButton(onClick = onDismiss) {
            Icon(CorvidIcons.Close, stringResource(Res.string.notices_dismiss), tint = content, modifier = Modifier.size(18.dp))
        }
    }
}
