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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.designsystem.CorvidShape
import com.verdenroz.corvid.core.designsystem.CorvidTheme

/** The quiet action: a hairline pill, for everything that is not the one primary action on screen. */
@Composable
fun PillButton(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 36.dp,
) {
    val colors = CorvidTheme.colors
    PillButton(onClick, modifier, height, contentPadding = PaddingValues(start = if (icon == null) 16.dp else 14.dp, end = 16.dp)) {
        icon?.let { Icon(it, contentDescription = null, tint = colors.ink, modifier = Modifier.size(height * 0.5f)) }
        Text(text, style = CorvidTheme.type.action, fontWeight = FontWeight.Medium, color = colors.ink)
    }
}

@Composable
fun PillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 36.dp,
    onClickLabel: String? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            modifier
                .height(height)
                .clip(CorvidShape.pill)
                .border(1.dp, CorvidTheme.colors.line, CorvidShape.pill)
                .clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
                .padding(contentPadding),
        content = content,
    )
}
