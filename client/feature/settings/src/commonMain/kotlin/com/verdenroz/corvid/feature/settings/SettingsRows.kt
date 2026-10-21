package com.verdenroz.corvid.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.designsystem.CorvidShape
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.LocalWideLayout
import com.verdenroz.corvid.core.designsystem.component.CorvidIcons
import com.verdenroz.corvid.core.designsystem.focusRing

@Composable
internal fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            title.uppercase(),
            style = CorvidTheme.type.label,
            color = CorvidTheme.colors.inkMuted,
            modifier = Modifier.padding(top = 32.dp, bottom = 10.dp).semantics { heading() },
        )
        HorizontalDivider(color = CorvidTheme.colors.line)
        content()
    }
}

@Composable
internal fun FactRow(
    label: String,
    value: String,
) {
    val muted = CorvidTheme.colors.inkMuted
    if (LocalWideLayout.current) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
            Text(label, style = CorvidTheme.type.name, color = muted, modifier = Modifier.width(132.dp))
            Text(value, style = CorvidTheme.type.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Text(label, style = CorvidTheme.type.meta, color = muted)
            Text(value, style = CorvidTheme.type.name, fontWeight = FontWeight.SemiBold)
        }
    }
    HorizontalDivider(color = CorvidTheme.colors.line)
}

@Composable
internal fun <T> ChoiceRow(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    detail: String? = null,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = CorvidTheme.type.name)
        detail?.let { Text(it, style = CorvidTheme.type.meta, color = CorvidTheme.colors.inkMuted) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option -> ChoicePill(label(option), option == selected) { onSelect(option) } }
        }
    }
    HorizontalDivider(color = CorvidTheme.colors.line)
}

@Composable
private fun ChoicePill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = CorvidTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .minimumInteractiveComponentSize()
                .height(36.dp)
                .focusRing(CorvidShape.pill)
                .clip(CorvidShape.pill)
                .then(if (selected) Modifier.background(colors.accentSoft) else Modifier.border(1.dp, colors.line, CorvidShape.pill))
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .padding(horizontal = 16.dp),
    ) {
        Text(
            text,
            style = CorvidTheme.type.action,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) colors.onAccentSoft else colors.ink,
        )
    }
}

@Composable
internal fun LinkRow(
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 14.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = CorvidTheme.type.name)
            Text(detail, style = CorvidTheme.type.meta, color = CorvidTheme.colors.inkMuted)
        }
        Icon(CorvidIcons.Arrow, contentDescription = null, tint = CorvidTheme.colors.inkMuted, modifier = Modifier.size(20.dp))
    }
    HorizontalDivider(color = CorvidTheme.colors.line)
}
