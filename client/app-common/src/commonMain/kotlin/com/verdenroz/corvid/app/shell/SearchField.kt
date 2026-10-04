package com.verdenroz.corvid.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.appcommon.resources.Res
import com.verdenroz.corvid.appcommon.resources.search_back
import com.verdenroz.corvid.appcommon.resources.search_placeholder
import com.verdenroz.corvid.appcommon.resources.search_shortcut
import com.verdenroz.corvid.core.designsystem.CorvidShape
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.CorvidIcons
import org.jetbrains.compose.resources.stringResource

/** What the search field shows and does; one instance feeds whichever bar is on screen. */
internal class SearchInput(
    val text: String,
    val onText: (String) -> Unit,
    val onSubmit: () -> Unit,
    val focus: FocusRequester,
)

@Composable
internal fun SearchField(
    input: SearchInput,
    shortcutHint: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = CorvidTheme.colors
    var focused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(colors.hover, CorvidShape.medium)
                .border(1.dp, if (focused) colors.accent else Color.Transparent, CorvidShape.medium)
                .padding(start = 12.dp, end = 8.dp),
    ) {
        Icon(CorvidIcons.Search, contentDescription = null, tint = colors.inkMuted, modifier = Modifier.size(20.dp))
        Box(Modifier.weight(1f)) {
            BasicTextField(
                value = input.text,
                onValueChange = input.onText,
                singleLine = true,
                textStyle = CorvidTheme.type.name.copy(color = colors.ink),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { input.onSubmit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(input.focus).onFocusChanged { focused = it.isFocused },
            )
            if (input.text.isEmpty()) {
                Text(stringResource(Res.string.search_placeholder), style = CorvidTheme.type.name, color = colors.inkMuted)
            }
        }
        if (shortcutHint && input.text.isEmpty()) {
            Text(
                stringResource(Res.string.search_shortcut),
                style = CorvidTheme.type.chip,
                color = colors.inkMuted,
                modifier = Modifier.border(1.dp, colors.line, CorvidShape.small).padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
}

/** The narrow search screen's own bar: Back, then the focused field. */
@Composable
internal fun SearchTopBar(
    input: SearchInput,
    onBack: () -> Unit,
) {
    val colors = CorvidTheme.colors
    LaunchedEffect(Unit) { input.focus.requestFocus() }
    Column(Modifier.background(colors.surface)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 16.dp, top = 4.dp, bottom = 6.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(onClickLabel = stringResource(Res.string.search_back), role = Role.Button, onClick = onBack),
            ) {
                Icon(CorvidIcons.Back, contentDescription = null, tint = colors.ink)
            }
            SearchField(input, shortcutHint = false, modifier = Modifier.weight(1f))
        }
        HorizontalDivider(color = colors.line)
    }
}
