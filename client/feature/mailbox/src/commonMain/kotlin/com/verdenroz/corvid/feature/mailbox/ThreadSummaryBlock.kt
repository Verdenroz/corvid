package com.verdenroz.corvid.feature.mailbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.designsystem.CorvidShape
import com.verdenroz.corvid.core.designsystem.CorvidSpacing
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.model.ThreadSummary
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.summary_caveat
import com.verdenroz.corvid.feature.mailbox.resources.summary_label
import com.verdenroz.corvid.feature.mailbox.resources.summary_show_all
import com.verdenroz.corvid.feature.mailbox.resources.summary_show_less
import org.jetbrains.compose.resources.stringResource

private const val COLLAPSED_LINES = 3

/** The text is model output derived from the mail, so it is drawn as a plain `Text`: no links, no markdown, nothing to act on. */
@Composable
internal fun ThreadSummaryBlock(
    summary: ThreadSummary,
    wide: Boolean,
) {
    val gutter = CorvidSpacing.readingGutter(wide)
    var expanded by remember(summary) { mutableStateOf(false) }
    var overflows by remember(summary) { mutableStateOf(false) }
    Column(
        Modifier.padding(start = gutter, end = gutter, bottom = 16.dp)
            .widthIn(max = 860.dp)
            .fillMaxWidth()
            .background(CorvidTheme.colors.rail, CorvidShape.medium)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(stringResource(Res.string.summary_label), style = CorvidTheme.type.label, color = CorvidTheme.colors.inkFaint)
        Text(stringResource(Res.string.summary_caveat), style = CorvidTheme.type.meta, color = CorvidTheme.colors.inkMuted)
        Text(
            summary.text,
            style = CorvidTheme.type.body,
            color = CorvidTheme.colors.ink,
            maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_LINES,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
            modifier = Modifier.padding(top = 8.dp),
        )
        if (overflows) {
            Text(
                stringResource(if (expanded) Res.string.summary_show_less else Res.string.summary_show_all),
                style = CorvidTheme.type.action,
                color = CorvidTheme.colors.accent,
                modifier = Modifier.clickable(role = Role.Button) { expanded = !expanded }.padding(top = 8.dp),
            )
        }
    }
}
