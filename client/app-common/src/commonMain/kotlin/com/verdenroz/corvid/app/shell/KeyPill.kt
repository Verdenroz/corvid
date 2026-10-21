package com.verdenroz.corvid.app.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.verdenroz.corvid.core.designsystem.CorvidSpacing
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.PillButton
import com.verdenroz.corvid.core.designsystem.focusRing
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
private fun KeyState.remaining(): Duration? = rememberRemaining(locksAt.collectAsState().value)

@Composable
internal fun KeyPill(key: KeyState) {
    val remaining = key.remaining() ?: return
    PillButton(
        onClick = key.onLock,
        onClickLabel = stringResource(Res.string.key_lock_now),
        contentPadding = PaddingValues(start = 7.dp, end = 13.dp),
    ) {
        KeyRing(fraction(remaining, key.timeout))
        KeyStatus(key.host, remaining)
    }
}

@Composable
internal fun KeyRingButton(key: KeyState) {
    val remaining = key.remaining() ?: return
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(40.dp)
                .focusRing(CircleShape)
                .clip(CircleShape)
                .clickable(onClickLabel = stringResource(Res.string.key_lock_now), role = Role.Button, onClick = key.onLock),
    ) { KeyRing(fraction(remaining, key.timeout)) }
}

@Composable
internal fun KeyStatusLine(key: KeyState) {
    val remaining = key.remaining() ?: return
    Row(Modifier.padding(start = CorvidSpacing.gutter(false), end = CorvidSpacing.gutter(false), bottom = 8.dp)) {
        KeyStatus(key.host, remaining)
    }
}

@Composable
private fun KeyStatus(
    host: String,
    remaining: Duration,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(host, style = CorvidTheme.type.meta, fontWeight = FontWeight.SemiBold, color = CorvidTheme.colors.ink)
        Text(
            stringResource(Res.string.key_status, countdown(remaining)),
            style = CorvidTheme.type.metaNumeric,
            color = CorvidTheme.colors.inkMuted,
        )
    }
}

@Composable
internal fun countdown(remaining: Duration): String =
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
