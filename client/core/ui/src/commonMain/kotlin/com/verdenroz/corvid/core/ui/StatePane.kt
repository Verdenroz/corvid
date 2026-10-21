package com.verdenroz.corvid.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.designsystem.CorvidShape
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.CorvidMark
import com.verdenroz.corvid.core.designsystem.component.QuietLink
import com.verdenroz.corvid.core.ui.resources.Res
import com.verdenroz.corvid.core.ui.resources.state_sign_out
import org.jetbrains.compose.resources.stringResource

/** A labelled fact under a state's statement, such as the server or the device. */
class StateFact(
    val label: String,
    val value: String,
)

/**
 * The full-screen states the gate swaps in (locked, unreachable, revoked, signing in): the mark,
 * one statement, the eye line, what is true, and whatever the user can do about it. [onSignOut]
 * adds Sign out under the actions, the way to another account or server from a state that holds.
 */
@Composable
fun StatePane(
    headline: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    facts: List<StateFact> = emptyList(),
    headlineColor: Color = CorvidTheme.colors.ink,
    onSignOut: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val colors = CorvidTheme.colors
    Box(
        modifier.fillMaxSize().background(colors.surface).verticalScroll(rememberScrollState()).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.widthIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CorvidMark(width = 48.dp)
            Text(headline, style = CorvidTheme.type.display, color = headlineColor)
            Box(Modifier.width(120.dp).height(3.dp).background(colors.accent, CorvidShape.pill))
            detail?.let { Text(it, style = CorvidTheme.type.body, color = colors.inkMuted) }
            if (facts.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    facts.forEach { fact ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(fact.label, style = CorvidTheme.type.meta, color = colors.inkMuted)
                            Text(fact.value, style = CorvidTheme.type.meta, fontWeight = FontWeight.SemiBold, color = colors.ink)
                        }
                    }
                }
            }
            Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
            onSignOut?.let { QuietLink(stringResource(Res.string.state_sign_out), it) }
        }
    }
}
