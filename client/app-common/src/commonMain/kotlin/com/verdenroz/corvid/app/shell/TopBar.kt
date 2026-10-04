package com.verdenroz.corvid.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.appcommon.resources.Res
import com.verdenroz.corvid.appcommon.resources.app_name
import com.verdenroz.corvid.appcommon.resources.search_placeholder
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.CorvidIcons
import com.verdenroz.corvid.core.designsystem.component.CorvidMark
import com.verdenroz.corvid.core.model.Identity
import com.verdenroz.corvid.core.ui.provenance.SenderAvatar
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration
import kotlin.time.Instant

/** What the key controls show; they collect [locksAt] themselves, so only they tick each second. */
internal class KeyState(
    val host: String,
    val locksAt: StateFlow<Instant?>,
    val timeout: Duration,
    val onLock: () -> Unit,
)

@Composable
internal fun Wordmark() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CorvidMark(width = 26.dp)
        Text(stringResource(Res.string.app_name), style = CorvidTheme.type.title)
    }
}

@Composable
internal fun WideTopBar(
    key: KeyState,
    identity: Identity?,
    search: SearchInput,
) {
    val colors = CorvidTheme.colors
    Column(Modifier.background(colors.surface)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth().height(60.dp).padding(start = 18.dp, end = 16.dp),
        ) {
            Box(Modifier.widthIn(min = 190.dp)) { Wordmark() }
            SearchField(search, shortcutHint = true, modifier = Modifier.weight(1f, fill = false).widthIn(max = 520.dp))
            Spacer(Modifier.weight(0.01f))
            KeyPill(key)
            identity?.let { AccountAvatar(it) }
        }
        HorizontalDivider(color = colors.line)
    }
}

@Composable
internal fun NarrowTopBar(
    key: KeyState,
    onSearch: () -> Unit,
) {
    val colors = CorvidTheme.colors
    Column(Modifier.background(colors.surface)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 4.dp, bottom = 6.dp),
        ) {
            Wordmark()
            Spacer(Modifier.weight(1f))
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(onClickLabel = stringResource(Res.string.search_placeholder), role = Role.Button, onClick = onSearch),
            ) {
                Icon(CorvidIcons.Search, contentDescription = null, tint = colors.inkMuted)
            }
            KeyRingButton(key)
        }
        KeyStatusLine(key)
    }
}

@Composable
private fun AccountAvatar(identity: Identity) {
    SenderAvatar(name = identity.name.ifBlank { identity.email }, isSource = false, seal = null, size = 32.dp)
}
