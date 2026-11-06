package com.verdenroz.corvid.app.shell

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.verdenroz.corvid.app.OutboxPending
import com.verdenroz.corvid.appcommon.resources.Res
import com.verdenroz.corvid.appcommon.resources.outbox_banner
import com.verdenroz.corvid.appcommon.resources.outbox_banner_attention
import com.verdenroz.corvid.appcommon.resources.outbox_banner_failed
import com.verdenroz.corvid.appcommon.resources.outbox_banner_held
import com.verdenroz.corvid.appcommon.resources.outbox_open
import com.verdenroz.corvid.core.data.outbox.OutboxAvailability
import com.verdenroz.corvid.core.designsystem.focusRingInset
import org.jetbrains.compose.resources.stringResource

internal fun OutboxPending.tone() = if (needsDecision || problem != null) BannerTone.Danger else BannerTone.Accent

/** One line above the shell counting the messages not sent yet; the danger tone when one waits on the user. */
@Composable
internal fun OutboxBannerBar(
    pending: OutboxPending,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val line =
        when {
            pending.problem == OutboxAvailability.HeldElsewhere -> stringResource(Res.string.outbox_banner_held)
            pending.problem != null -> stringResource(Res.string.outbox_banner_failed)
            pending.needsDecision -> stringResource(Res.string.outbox_banner_attention, pending.count)
            else -> stringResource(Res.string.outbox_banner, pending.count)
        }
    ShellBannerBar(
        tone = pending.tone(),
        text = buildAnnotatedString { withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(line) } },
        onDismiss = onDismiss,
        modifier =
            Modifier
                .focusRingInset()
                .clickable(onClickLabel = stringResource(Res.string.outbox_open), role = Role.Button, onClick = onOpen),
    )
}
