package com.verdenroz.corvid.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.designsystem.CorvidShape
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.drawIndicator
import com.verdenroz.corvid.feature.settings.resources.Res
import com.verdenroz.corvid.feature.settings.resources.settings_about
import com.verdenroz.corvid.feature.settings.resources.settings_account
import com.verdenroz.corvid.feature.settings.resources.settings_appearance
import com.verdenroz.corvid.feature.settings.resources.settings_devices
import com.verdenroz.corvid.feature.settings.resources.settings_filters
import com.verdenroz.corvid.feature.settings.resources.settings_notices
import com.verdenroz.corvid.feature.settings.resources.settings_security
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The sections of Settings in order: one column narrow, a list beside the chosen one wide. */
enum class SettingsTopic(val title: StringResource) {
    Account(Res.string.settings_account),
    Appearance(Res.string.settings_appearance),
    Security(Res.string.settings_security),
    Devices(Res.string.settings_devices),
    Filters(Res.string.settings_filters),
    Notices(Res.string.settings_notices),
    About(Res.string.settings_about),
}

@Composable
internal fun TopicList(
    current: SettingsTopic,
    onSelect: (SettingsTopic) -> Unit,
) {
    Column(Modifier.width(200.dp).fillMaxHeight().padding(start = 12.dp, end = 12.dp, top = 20.dp)) {
        SettingsTopic.entries.forEach { topic -> TopicItem(stringResource(topic.title), topic == current) { onSelect(topic) } }
    }
}

@Composable
private fun TopicItem(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = CorvidTheme.colors
    Text(
        title,
        style = CorvidTheme.type.name,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = if (selected) colors.ink else colors.inkMuted,
        modifier =
            Modifier
                .fillMaxWidth()
                .height(38.dp)
                .clip(CorvidShape.small)
                .clickable(role = Role.Tab, onClick = onClick)
                .semantics { this.selected = selected }
                .drawBehind { if (selected) drawIndicator(colors.accent, centerX = 22.dp.toPx()) }
                .padding(horizontal = 12.dp)
                .wrapContentHeight(),
    )
}
