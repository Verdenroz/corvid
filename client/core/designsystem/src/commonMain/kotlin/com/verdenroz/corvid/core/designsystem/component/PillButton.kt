package com.verdenroz.corvid.core.designsystem.component

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.designsystem.CorvidShape
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.focusRing

/**
 * A hairline pill, a step below the primary action and above a [QuietLink]. [height] is only what
 * is drawn: the layout keeps a full touch target around it, so a short pill keeps its look.
 */
@Composable
fun PillButton(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 36.dp,
    enabled: Boolean = true,
) {
    val colors = CorvidTheme.colors
    val padding = PaddingValues(start = if (icon == null) 16.dp else 14.dp, end = 16.dp)
    PillButton(onClick, modifier, height, enabled = enabled, contentPadding = padding) {
        icon?.let { Icon(it, contentDescription = null, tint = colors.ink, modifier = Modifier.size(height * 0.5f)) }
        Text(text, style = CorvidTheme.type.action, fontWeight = FontWeight.Medium, color = colors.ink)
    }
}

@Composable
fun PillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 36.dp,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    borderColor: Color = CorvidTheme.colors.line,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            modifier
                .minimumInteractiveComponentSize()
                .height(height)
                .focusRing(CorvidShape.pill)
                .alpha(if (enabled) 1f else 0.45f)
                .clip(CorvidShape.pill)
                .border(1.dp, borderColor, CorvidShape.pill)
                .clickable(enabled = enabled, onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
                .padding(contentPadding),
        content = content,
    )
}
