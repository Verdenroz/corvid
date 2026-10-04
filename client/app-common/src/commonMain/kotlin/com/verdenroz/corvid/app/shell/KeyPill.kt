package com.verdenroz.corvid.app.shell

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.appcommon.resources.Res
import com.verdenroz.corvid.appcommon.resources.key_countdown
import com.verdenroz.corvid.appcommon.resources.key_lock_now
import com.verdenroz.corvid.appcommon.resources.key_status
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.ui.provenance.KeyRing
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** What is left of the idle window, ticking once a second; null while no deadline is set. */
@Composable
internal fun rememberRemaining(locksAt: Instant?): Duration? {
    val remaining by produceState<Duration?>(null, locksAt) {
        while (locksAt != null) {
            value = (locksAt - Clock.System.now()).coerceAtLeast(Duration.ZERO)
            delay(1.seconds)
        }
        value = null
    }
    return remaining
}

@Composable
internal fun KeyPill(
    host: String,
    remaining: Duration,
    timeout: Duration,
    onLock: () -> Unit,
) {
    val colors = CorvidTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        modifier =
            Modifier
                .height(36.dp)
                .clip(RoundedCornerShape(50))
                .border(1.dp, colors.line, RoundedCornerShape(50))
                .clickable(onClickLabel = stringResource(Res.string.key_lock_now), role = Role.Button, onClick = onLock)
                .padding(start = 7.dp, end = 13.dp),
    ) {
        KeyRing(fraction(remaining, timeout))
        KeyStatus(host, remaining)
    }
}

@Composable
internal fun KeyRingButton(
    remaining: Duration,
    timeout: Duration,
    onLock: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClickLabel = stringResource(Res.string.key_lock_now), role = Role.Button, onClick = onLock),
    ) { KeyRing(fraction(remaining, timeout)) }
}

@Composable
internal fun KeyStatus(
    host: String,
    remaining: Duration,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(host, style = CorvidTheme.type.meta, fontWeight = FontWeight.SemiBold, color = CorvidTheme.colors.ink)
        Text(
            stringResource(Res.string.key_status, countdown(remaining)),
            style = CorvidTheme.type.meta.copy(fontFeatureSettings = "tnum"),
            color = CorvidTheme.colors.inkMuted,
        )
    }
}

@Composable
private fun countdown(remaining: Duration): String =
    remaining.toComponents {
            minutes,
            seconds,
            _,
        ->
        stringResource(Res.string.key_countdown, minutes.toString(), seconds.toString().padStart(2, '0'))
    }

private fun fraction(
    remaining: Duration,
    timeout: Duration,
): Float = (remaining / timeout).toFloat()
