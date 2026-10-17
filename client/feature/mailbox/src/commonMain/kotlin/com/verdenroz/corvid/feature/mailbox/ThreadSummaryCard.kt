package com.verdenroz.corvid.feature.mailbox

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.data.stamp
import com.verdenroz.corvid.core.designsystem.CorvidShape
import com.verdenroz.corvid.core.designsystem.CorvidSpacing
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.CorvidIcons
import com.verdenroz.corvid.core.model.ThreadSummary
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.custody_summarised
import com.verdenroz.corvid.feature.mailbox.resources.custody_summarised_by
import com.verdenroz.corvid.feature.mailbox.resources.summary_caveat
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.Instant

/** The model and the local time it wrote the summary; the model is left out when an older server did not name it. */
internal fun summarisedValue(
    summary: ThreadSummary,
    zone: TimeZone,
    now: Instant,
): UiText {
    val at = stamp(summary.generatedAt, zone, now)
    return if (summary.model.isEmpty()) at else uiText(Res.string.custody_summarised_by, summary.model, at)
}

/** The thread's summary in the custody strip's own look. The text is model output derived from the mail, so it is a plain `Text`. */
@Composable
internal fun ThreadSummaryCard(
    summary: ThreadSummary,
    wide: Boolean,
) {
    val colors = CorvidTheme.colors
    val gutter = CorvidSpacing.readingGutter(wide)
    val value = remember(summary) { summarisedValue(summary, TimeZone.currentSystemDefault(), Clock.System.now()) }
    Column(
        Modifier
            .padding(start = gutter, end = gutter, bottom = 16.dp)
            .widthIn(max = 860.dp)
            .fillMaxWidth()
            .clip(CorvidShape.medium)
            .border(1.dp, CorvidTheme.sheen, CorvidShape.medium),
    ) {
        Cell(CorvidIcons.Headers, stringResource(Res.string.custody_summarised), value.asString(), null, Modifier.fillMaxWidth())
        HorizontalDivider(color = colors.line)
        Column(Modifier.fillMaxWidth().background(colors.rail).padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(summary.text, style = CorvidTheme.type.body, color = colors.ink)
            Text(
                stringResource(Res.string.summary_caveat),
                style = CorvidTheme.type.meta,
                color = colors.inkFaint,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
