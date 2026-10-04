package com.verdenroz.corvid.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.appcommon.resources.Res
import com.verdenroz.corvid.appcommon.resources.app_name
import com.verdenroz.corvid.appcommon.resources.search
import com.verdenroz.corvid.appcommon.resources.search_placeholder
import com.verdenroz.corvid.appcommon.resources.search_shortcut
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.CorvidIcons
import com.verdenroz.corvid.core.designsystem.component.CorvidMark
import com.verdenroz.corvid.core.model.Identity
import com.verdenroz.corvid.core.ui.provenance.SenderAvatar
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration

/** What the key pill shows; [remaining] is null in the moment before the idle timer first starts. */
internal class KeyState(
    val host: String,
    val remaining: Duration?,
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

/** The search field is drawn but not wired yet. */
@Composable
internal fun WideTopBar(
    key: KeyState,
    identity: Identity?,
) {
    val colors = CorvidTheme.colors
    Column(Modifier.background(colors.surface)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth().height(60.dp).padding(start = 18.dp, end = 16.dp),
        ) {
            Box(Modifier.widthIn(min = 190.dp)) { Wordmark() }
            SearchField(Modifier.weight(1f, fill = false).widthIn(max = 520.dp))
            Spacer(Modifier.weight(0.01f))
            key.remaining?.let { KeyPill(key.host, it, key.timeout, key.onLock) }
            identity?.let { AccountAvatar(it) }
        }
        HorizontalDivider(color = colors.line)
    }
}

@Composable
internal fun NarrowTopBar(key: KeyState) {
    val colors = CorvidTheme.colors
    Column(Modifier.background(colors.surface)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 4.dp, bottom = 6.dp),
        ) {
            Wordmark()
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Icon(CorvidIcons.Search, contentDescription = stringResource(Res.string.search), tint = colors.inkMuted)
            }
            key.remaining?.let { KeyRingButton(it, key.timeout, key.onLock) }
        }
        key.remaining?.let {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            ) {
                KeyStatus(key.host, it)
            }
        }
    }
}

@Composable
private fun SearchField(modifier: Modifier) {
    val colors = CorvidTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier =
            modifier.fillMaxWidth().height(
                40.dp,
            ).background(colors.hover, RoundedCornerShape(12.dp)).padding(start = 12.dp, end = 8.dp),
    ) {
        Icon(CorvidIcons.Search, contentDescription = null, tint = colors.inkMuted, modifier = Modifier.size(20.dp))
        Text(
            stringResource(Res.string.search_placeholder),
            style = CorvidTheme.type.name,
            color = colors.inkMuted,
            modifier = Modifier.weight(1f),
        )
        Text(
            stringResource(Res.string.search_shortcut),
            style = CorvidTheme.type.chip,
            color = colors.inkMuted,
            modifier = Modifier.border(1.dp, colors.line, RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun AccountAvatar(identity: Identity) {
    SenderAvatar(name = identity.name.ifBlank { identity.email }, isSource = false, seal = null, size = 32.dp)
}
