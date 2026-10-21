package com.verdenroz.corvid.core.designsystem.component

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.focusRing
import com.verdenroz.corvid.core.designsystem.touchTarget

/** An icon button that names itself on hover and to a screen reader. */
@Composable
fun ActionIcon(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = CorvidTheme.colors.inkMuted,
    size: Dp = touchTarget(),
    enabled: Boolean = true,
    iconSize: Dp = 20.dp,
) {
    LabelTooltip(label, modifier) {
        IconButton(onClick, Modifier.size(size).focusRing(CircleShape), enabled = enabled) {
            Icon(icon, label, tint = if (enabled) tint else CorvidTheme.colors.outline, modifier = Modifier.size(iconSize))
        }
    }
}
