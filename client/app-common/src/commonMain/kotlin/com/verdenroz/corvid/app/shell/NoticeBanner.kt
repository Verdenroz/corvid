package com.verdenroz.corvid.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.app.NoticeBanner
import com.verdenroz.corvid.appcommon.resources.Res
import com.verdenroz.corvid.appcommon.resources.notices_dismiss
import com.verdenroz.corvid.appcommon.resources.notices_open
import com.verdenroz.corvid.core.designsystem.CorvidSpacing
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.CorvidIcons
import com.verdenroz.corvid.core.model.NoticeKind
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.core.ui.LocalWideLayout
import org.jetbrains.compose.resources.stringResource

/** One line above the shell for the newest unread notice; a sign-in takes the accent, anything that went wrong the danger tone. */
@Composable
internal fun NoticeBannerBar(
    banner: NoticeBanner,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CorvidTheme.colors
    val danger = banner.newest.kind != NoticeKind.SignIn
    val background = if (danger) colors.dangerSoft else colors.accentSoft
    val content = if (danger) colors.onDangerSoft else colors.onAccentSoft
    val line = banner.line.asString()
    val text =
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(line) }
            if (banner.count == 1) append("  ${banner.newest.detail}")
        }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(background)
                .clickable(onClickLabel = stringResource(Res.string.notices_open), role = Role.Button, onClick = onOpen)
                .heightIn(min = 44.dp)
                .padding(start = CorvidSpacing.gutter(LocalWideLayout.current)),
    ) {
        Text(
            text,
            style = CorvidTheme.type.meta,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss) {
            Icon(CorvidIcons.Close, stringResource(Res.string.notices_dismiss), tint = content, modifier = Modifier.size(18.dp))
        }
    }
}
