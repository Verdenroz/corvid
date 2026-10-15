package com.verdenroz.corvid.feature.digest

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.data.stamp
import com.verdenroz.corvid.core.designsystem.CorvidShape
import com.verdenroz.corvid.core.designsystem.CorvidSpacing
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.model.DigestItem
import com.verdenroz.corvid.core.model.ListedItem
import com.verdenroz.corvid.core.model.items
import com.verdenroz.corvid.core.model.lists
import com.verdenroz.corvid.core.model.people
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.core.ui.LocalWideLayout
import com.verdenroz.corvid.core.ui.PageHeader
import com.verdenroz.corvid.core.ui.QuietPane
import com.verdenroz.corvid.core.ui.provenance.InitialsTile
import com.verdenroz.corvid.feature.digest.resources.Res
import com.verdenroz.corvid.feature.digest.resources.digest_back
import com.verdenroz.corvid.feature.digest.resources.digest_caveat
import com.verdenroz.corvid.feature.digest.resources.digest_empty
import com.verdenroz.corvid.feature.digest.resources.digest_lists
import com.verdenroz.corvid.feature.digest.resources.digest_more
import com.verdenroz.corvid.feature.digest.resources.digest_more_count
import com.verdenroz.corvid.feature.digest.resources.digest_people
import com.verdenroz.corvid.feature.digest.resources.digest_span
import com.verdenroz.corvid.feature.digest.resources.digest_title
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

private const val LIST_ROWS = 6
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
        PageHeader(null, stringResource(Res.string.digest_back), onBack)
        when (val current = state) {
            is DigestState.Loading -> Unit
            is DigestState.Empty -> QuietPane(stringResource(Res.string.digest_empty))
            is DigestState.Failed -> QuietPane(current.message.asString())
            is DigestState.Ready -> Briefing(current, onOpenEmail)
        }
    }
}

@Composable
private fun Briefing(
    ready: DigestState.Ready,
    onOpenEmail: (String) -> Unit,
) {
    val gutter = CorvidSpacing.gutter(LocalWideLayout.current)
    var expanded by rememberSaveable { mutableStateOf(false) }
    val people = ready.digest.people
    val lists = ready.digest.lists
    val listed = if (expanded) lists else lists.take(LIST_ROWS)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = gutter, vertical = 8.dp)) {
        item { Heading(ready) }
        if (people.isNotEmpty()) {
            item {
                Section(
                    stringResource(Res.string.digest_people),
                    if (ready.anySummary) stringResource(Res.string.digest_caveat) else null,
                )
            }
            items(people, key = { it.emailId }) { Person(it) { onOpenEmail(it.emailId) } }
        }
        if (people.isNotEmpty() && lists.isNotEmpty()) {
            item { HorizontalDivider(Modifier.widthIn(max = COLUMN_WIDTH).padding(top = 14.dp), color = CorvidTheme.colors.line) }
        }
        if (lists.isNotEmpty()) {
            item { Section(stringResource(Res.string.digest_lists), lists.size.toString()) }
            items(listed, key = { it.item.emailId }) { Listed(it) { onOpenEmail(it.item.emailId) } }
            val hidden = lists.size - listed.size
            if (hidden > 0) item { More(hidden) { expanded = true } }
        }
    }
}

@Composable
private fun Heading(ready: DigestState.Ready) {
    val zone = TimeZone.currentSystemDefault()
    val now = Clock.System.now()
    val digest = ready.digest
    val count = digest.items.size
    val span = UiText.Plural(Res.plurals.digest_span, count, listOf(stamp(digest.from, zone, now), stamp(digest.to, zone, now), count))
    Column(Modifier.widthIn(max = COLUMN_WIDTH).padding(top = 8.dp)) {
        Text(stringResource(Res.string.digest_title), style = CorvidTheme.type.display)
        Text(span.asString(), style = CorvidTheme.type.meta, color = CorvidTheme.colors.inkMuted, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun Section(
    title: String,
    aside: String?,
) {
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.widthIn(max = COLUMN_WIDTH).fillMaxWidth().padding(top = 20.dp, bottom = 8.dp),
    ) {
        Text(title, style = CorvidTheme.type.dayHeader)
        aside?.let { Text(it, style = CorvidTheme.type.meta, color = CorvidTheme.colors.inkFaint) }
    }
}

/** The name and the summary came from mail or a model, so the paragraph is a plain `Text`: no links, only the row opens. */
@Composable
private fun Person(
    item: DigestItem,
    onOpen: () -> Unit,
) {
    val colors = CorvidTheme.colors
    val paragraph =
        buildAnnotatedString {
            withStyle(SpanStyle(color = colors.ink, fontWeight = FontWeight.SemiBold)) { append(item.displayName) }
            append(' ')
            append(item.summary ?: item.subject)
        }
    Text(
        paragraph,
        style = CorvidTheme.type.body,
        color = colors.inkMuted,
        modifier =
            Modifier.widthIn(
                max = COLUMN_WIDTH,
            ).fillMaxWidth().clickable(role = Role.Button, onClick = onOpen).padding(vertical = 10.dp),
    )
}

@Composable
private fun Listed(
    row: ListedItem,
    onOpen: () -> Unit,
) {
    val colors = CorvidTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier =
            Modifier.widthIn(
                max = COLUMN_WIDTH,
            ).fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onOpen),
    ) {
        InitialsTile(row.item.displayName, 22.dp)
        Text(
            row.item.subject,
            style = CorvidTheme.type.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        row.label?.let {
            Text(
                it,
                style = CorvidTheme.type.chip,
                color = colors.onAccentSoft,
                maxLines = 1,
                modifier = Modifier.background(colors.chip, CorvidShape.small).padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun More(
    hidden: Int,
    onExpand: () -> Unit,
) {
    val colors = CorvidTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier =
            Modifier.widthIn(
                max = COLUMN_WIDTH,
            ).fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onExpand),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.defaultMinSize(22.dp, 22.dp).border(1.dp, colors.line, CorvidShape.small).padding(horizontal = 3.dp),
        ) {
            Text(stringResource(Res.string.digest_more_count, hidden), style = CorvidTheme.type.chip, color = colors.inkMuted, maxLines = 1)
        }
        Text(pluralStringResource(Res.plurals.digest_more, hidden, hidden), style = CorvidTheme.type.name, color = colors.inkMuted)
    }
}
