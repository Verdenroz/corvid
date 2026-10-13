package com.verdenroz.corvid.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.designsystem.CorvidTheme

/** A secondary action that is only text, so a screen's one primary action stays the only weight on it. */
@Composable
fun QuietLink(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = CorvidTheme.type.action,
        color = CorvidTheme.colors.inkMuted,
        textDecoration = TextDecoration.Underline,
        modifier = modifier.heightIn(min = 40.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 10.dp),
    )
}
