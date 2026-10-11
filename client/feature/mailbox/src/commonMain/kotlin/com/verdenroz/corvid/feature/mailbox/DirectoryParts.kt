package com.verdenroz.corvid.feature.mailbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.data.Directory
import com.verdenroz.corvid.core.data.clockTime
import com.verdenroz.corvid.core.data.dayHeading
import com.verdenroz.corvid.core.designsystem.CorvidSpacing
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.core.ui.LocalWideLayout
import com.verdenroz.corvid.core.ui.QuietPane
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.directory_window
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.pluralStringResource
import kotlin.time.Clock
import kotlin.time.Instant

/** Loads [viewModel] while shown; [empty] is said in place of a list with nothing in it, under the caption naming the window. */
@Composable
internal fun DirectoryList(
    viewModel: DirectoryViewModel,
    empty: String,
    isEmpty: (Directory) -> Boolean,
    rows: LazyListScope.(Directory) -> Unit,
) {
    DisposableEffect(viewModel) {
        viewModel.shown(true)
        onDispose { viewModel.shown(false) }
    }
    val state by viewModel.state.collectAsState()
    when (val current = state) {
        is DirectoryUiState.Loading -> Unit
        is DirectoryUiState.Failed -> QuietPane(current.message.asString())
        is DirectoryUiState.Loaded -> {
            val directory = current.directory
            LazyColumn(Modifier.fillMaxSize().background(CorvidTheme.colors.surface)) {
                item(key = "window") { WindowCaption(directory.window) }
                if (isEmpty(directory)) {
                    item(key = "empty") { QuietPane(empty, Modifier.fillParentMaxSize()) }
                } else {
                    rows(directory)
                }
            }
        }
    }
}

@Composable
private fun WindowCaption(window: Int) {
    Text(
        pluralStringResource(Res.plurals.directory_window, window, window),
        style = CorvidTheme.type.meta,
        color = CorvidTheme.colors.inkFaint,
        modifier = Modifier.padding(horizontal = CorvidSpacing.gutter(LocalWideLayout.current), vertical = 12.dp),
    )
}

/** One sender: [leading] avatar, name over [detail], and when [last] arrived over [tally] at the end. */
@Composable
internal fun DirectoryRow(
    name: String,
    detail: String,
    detailColor: Color,
    last: Instant,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
    tally: @Composable () -> Unit,
) {
    val colors = CorvidTheme.colors
    val gutter = CorvidSpacing.gutter(LocalWideLayout.current)
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.heightIn(min = 64.dp).padding(horizontal = gutter, vertical = 10.dp),
        ) {
            leading()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    name,
                    style = CorvidTheme.type.name,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(detail, style = CorvidTheme.type.meta, color = detailColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(whenArrived(last), style = CorvidTheme.type.metaNumeric, color = colors.inkFaint)
                tally()
            }
        }
        HorizontalDivider(color = colors.line)
    }
}

/** The time for today, otherwise the same day label the ledger's header would give it. */
@Composable
internal fun whenArrived(at: Instant): String {
    val zone = remember { TimeZone.currentSystemDefault() }
    return whenArrived(at, zone, Clock.System.now().toLocalDateTime(zone).date)
}

@Composable
internal fun whenArrived(
    at: Instant,
    zone: TimeZone,
    today: LocalDate,
): String {
    val local = at.toLocalDateTime(zone)
    return if (local.date == today) clockTime(local.hour, local.minute).asString() else dayHeading(local.date, today).title.asString()
}
