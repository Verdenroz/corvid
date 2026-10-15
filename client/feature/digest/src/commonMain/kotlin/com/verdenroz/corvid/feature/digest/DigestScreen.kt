package com.verdenroz.corvid.feature.digest

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.data.stamp
import com.verdenroz.corvid.core.designsystem.CorvidSpacing
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.model.Digest
import com.verdenroz.corvid.core.model.DigestItem
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.core.ui.LocalWideLayout
import com.verdenroz.corvid.core.ui.PageHeader
import com.verdenroz.corvid.core.ui.QuietPane
import com.verdenroz.corvid.feature.digest.resources.Res
import com.verdenroz.corvid.feature.digest.resources.digest_back
import com.verdenroz.corvid.feature.digest.resources.digest_caveat
import com.verdenroz.corvid.feature.digest.resources.digest_empty
import com.verdenroz.corvid.feature.digest.resources.digest_other
import com.verdenroz.corvid.feature.digest.resources.digest_span
import com.verdenroz.corvid.feature.digest.resources.digest_title
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

private const val SUMMARY_LINES = 4
private val COLUMN_WIDTH = 640.dp

/** The server's overnight digest; [onOpenEmail] takes the id of the message a row names. */
@Composable
fun DigestScreen(
    viewModel: DigestViewModel,
    onBack: () -> Unit,
    onOpenEmail: (String) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    Column(modifier = Modifier.fillMaxSize().background(CorvidTheme.colors.surface)) {
        PageHeader(stringResource(Res.string.digest_title), stringResource(Res.string.digest_back), onBack)
        when (val current = state) {
            is DigestState.Loading -> Unit
            is DigestState.Empty -> QuietPane(stringResource(Res.string.digest_empty))
            is DigestState.Failed -> QuietPane(current.message.asString())
            is DigestState.Ready -> DigestList(current, onOpenEmail)
        }
    }
}

@Composable
private fun DigestList(
    ready: DigestState.Ready,
    onOpenEmail: (String) -> Unit,
) {
    val gutter = CorvidSpacing.gutter(LocalWideLayout.current)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = gutter, vertical = 8.dp)) {
        item { Span(ready.digest, ready.anySummary) }
        ready.digest.groups.forEach { group ->
            item {
                Text(
                    group.label ?: stringResource(Res.string.digest_other),
                    style = CorvidTheme.type.label,
                    color = CorvidTheme.colors.inkFaint,
                    modifier = Modifier.widthIn(max = COLUMN_WIDTH).padding(top = 20.dp, bottom = 4.dp),
                )
            }
            group.items.forEach { entry ->
                item(key = entry.emailId) { DigestRow(entry, onOpenEmail) }
            }
        }
    }
}

@Composable
private fun Span(
    digest: Digest,
    anySummary: Boolean,
) {
    val zone = TimeZone.currentSystemDefault()
    val now = Clock.System.now()
    val span = uiText(Res.string.digest_span, stamp(digest.from, zone, now), stamp(digest.to, zone, now))
    Column(Modifier.widthIn(max = COLUMN_WIDTH).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(span.asString(), style = CorvidTheme.type.meta, color = CorvidTheme.colors.inkMuted)
        if (anySummary) Text(stringResource(Res.string.digest_caveat), style = CorvidTheme.type.meta, color = CorvidTheme.colors.inkFaint)
    }
}

/** Every string here came from mail or a model, so each is a plain `Text`: no links, nothing to act on but the row. */
@Composable
private fun DigestRow(
    item: DigestItem,
    onOpenEmail: (String) -> Unit,
) {
    Column(Modifier.widthIn(max = COLUMN_WIDTH)) {
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxWidth().clickable { onOpenEmail(item.emailId) }.padding(vertical = 12.dp),
        ) {
            Text(
                item.from,
                style = CorvidTheme.type.meta,
                color = CorvidTheme.colors.inkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.subject,
                style = CorvidTheme.type.name,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            item.summary?.let {
                Text(
                    it,
                    style = CorvidTheme.type.body,
                    color = CorvidTheme.colors.inkMuted,
                    maxLines = SUMMARY_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HorizontalDivider(color = CorvidTheme.colors.line)
    }
}
