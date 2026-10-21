package com.verdenroz.corvid.app.shell

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.verdenroz.corvid.app.NoticeBanner
import com.verdenroz.corvid.appcommon.resources.Res
import com.verdenroz.corvid.appcommon.resources.notices_open
import com.verdenroz.corvid.core.designsystem.focusRingInset
import com.verdenroz.corvid.core.model.NoticeKind
import com.verdenroz.corvid.core.text.asString
import org.jetbrains.compose.resources.stringResource

/** One line above the shell for the newest unread notice; a sign-in takes the accent, anything that went wrong the danger tone. */
@Composable
internal fun NoticeBannerBar(
    banner: NoticeBanner,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val line = banner.line.asString()
    val text =
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(line) }
            if (banner.count == 1) append("  ${banner.newest.detail}")
        }
    ShellBannerBar(
        tone = if (banner.newest.kind != NoticeKind.SignIn) BannerTone.Danger else BannerTone.Accent,
        text = text,
        onDismiss = onDismiss,
        modifier =
            Modifier
                .focusRingInset()
                .clickable(onClickLabel = stringResource(Res.string.notices_open), role = Role.Button, onClick = onOpen),
    )
}
