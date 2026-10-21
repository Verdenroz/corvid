package com.verdenroz.corvid.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.QuietLink
import com.verdenroz.corvid.core.ui.resources.Res
import com.verdenroz.corvid.core.ui.resources.pane_waiting
import com.verdenroz.corvid.core.ui.resources.try_again
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds

private val LoadingGrace = 600.milliseconds

/** One muted line centred in an otherwise empty screen: an empty list, a failure, a placeholder. [onRetry] adds Try again. */
@Composable
fun QuietPane(
    text: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 420.dp)) {
            Text(text, style = CorvidTheme.type.body, color = CorvidTheme.colors.inkMuted)
            onRetry?.let { QuietLink(stringResource(Res.string.try_again), it) }
        }
    }
}

/** One muted line under content that did load, saying what did not, with Try again. */
@Composable
fun FailedLine(
    text: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier,
    ) {
        Text(text, style = CorvidTheme.type.meta, color = CorvidTheme.colors.inkMuted, modifier = Modifier.weight(1f, fill = false))
        QuietLink(stringResource(Res.string.try_again), onRetry)
    }
}

/** Blank while a load is quick, then a line saying the server has not answered yet. */
@Composable
fun LoadingPane(modifier: Modifier = Modifier) {
    var waited by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(LoadingGrace)
        waited = true
    }
    if (waited) QuietPane(stringResource(Res.string.pane_waiting), modifier)
}
