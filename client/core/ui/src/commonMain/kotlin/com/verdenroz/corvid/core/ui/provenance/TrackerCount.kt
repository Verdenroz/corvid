package com.verdenroz.corvid.core.ui.provenance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.CorvidIcons
import com.verdenroz.corvid.core.ui.resources.Res
import com.verdenroz.corvid.core.ui.resources.trackers_removed
import org.jetbrains.compose.resources.pluralStringResource

@Composable
fun TrackerCount(
    count: Int,
    modifier: Modifier = Modifier,
) {
    val description = pluralStringResource(Res.plurals.trackers_removed, count, count)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
    ) {
        Icon(CorvidIcons.EyeOff, contentDescription = null, tint = CorvidTheme.colors.inkFaint, modifier = Modifier.size(14.dp))
        Text(count.toString(), style = CorvidTheme.type.metaNumeric, color = CorvidTheme.colors.inkFaint)
    }
}
