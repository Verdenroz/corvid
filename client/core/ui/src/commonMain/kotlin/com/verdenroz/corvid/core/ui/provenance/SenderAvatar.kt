package com.verdenroz.corvid.core.ui.provenance

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.CorvidIcons
import com.verdenroz.corvid.core.designsystem.oklch
import com.verdenroz.corvid.core.model.Seal
import com.verdenroz.corvid.core.ui.resources.Res
import com.verdenroz.corvid.core.ui.resources.seal_failed
import com.verdenroz.corvid.core.ui.resources.seal_signed
import com.verdenroz.corvid.core.ui.resources.seal_signed_by
import org.jetbrains.compose.resources.stringResource

/** Circles are people, rounded squares are sources; the badge is the server's own verdict on the sender. */
@Composable
fun SenderAvatar(
    name: String,
    isSource: Boolean,
    seal: Seal?,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    onClick: (() -> Unit)? = null,
) {
    val shape = if (isSource) RoundedCornerShape(size * 0.28f) else CircleShape
    Box(modifier.size(size)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(size)
                    .background(avatarColor(name, CorvidTheme.colors.isDark), shape)
                    .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)),
        ) {
            Text(
                initial(name),
                style = if (isSource) CorvidTheme.type.name.copy(fontFamily = CorvidTheme.type.title.fontFamily) else CorvidTheme.type.name,
                fontWeight = if (isSource) FontWeight.Bold else FontWeight.SemiBold,
                fontSize = (size.value * 0.39f).sp,
                color = CorvidTheme.colors.onAccent,
            )
        }
        seal?.let { SealBadge(it, size, Modifier.align(Alignment.BottomEnd)) }
    }
}

@Composable
private fun SealBadge(
    seal: Seal,
    avatarSize: Dp,
    modifier: Modifier,
) {
    val colors = CorvidTheme.colors
    val (icon, tint) =
        when (seal) {
            is Seal.Signed -> CorvidIcons.Seal to colors.accent
            is Seal.Failed -> CorvidIcons.Alert to colors.danger
            is Seal.Unsigned -> return
        }
    val badge = avatarSize / 2
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.offset(4.dp, 4.dp).size(badge).background(colors.surface, CircleShape),
    ) {
        Icon(icon, contentDescription = sealDescription(seal), tint = tint, modifier = Modifier.size(badge * 0.78f))
    }
}

@Composable
fun sealDescription(seal: Seal): String? =
    when (seal) {
        is Seal.Signed -> seal.signer?.let { stringResource(Res.string.seal_signed_by, it) } ?: stringResource(Res.string.seal_signed)
        is Seal.Failed -> stringResource(Res.string.seal_failed)
        is Seal.Unsigned -> null
    }

/** A stable hue per name, so a sender keeps its colour across sessions and devices. */
internal fun avatarColor(
    name: String,
    dark: Boolean,
): Color {
    val hue = name.fold(7) { acc, char -> (acc * 31 + char.code) % 360 }.toFloat()
    return if (dark) oklch(0.72f, 0.1f, hue) else oklch(0.55f, 0.12f, hue)
}

internal fun initial(name: String): String = (name.firstOrNull { it.isLetterOrDigit() } ?: name.firstOrNull() ?: ' ').uppercase()
