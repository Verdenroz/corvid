package com.verdenroz.corvid.app.shell

import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import com.verdenroz.corvid.app.DigestBanner
import com.verdenroz.corvid.appcommon.resources.Res
import com.verdenroz.corvid.appcommon.resources.digest_open
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.text.asString
import org.jetbrains.compose.resources.stringResource

/** One line above the shell offering the latest digest. */
@Composable
internal fun DigestBannerBar(
    banner: DigestBanner,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val open = stringResource(Res.string.digest_open)
    ShellBannerBar(
        tone = BannerTone.Accent,
        text = AnnotatedString(banner.line.asString()),
        onDismiss = onDismiss,
        modifier = Modifier.clickable(onClickLabel = open, role = Role.Button, onClick = onOpen),
    ) { content ->
        TextButton(onClick = onOpen) { Text(open, style = CorvidTheme.type.action, color = content) }
    }
}
