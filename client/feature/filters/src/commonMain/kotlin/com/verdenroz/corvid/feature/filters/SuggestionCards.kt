package com.verdenroz.corvid.feature.filters

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.designsystem.CorvidShape
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.model.MailboxRef
import com.verdenroz.corvid.core.model.RuleSuggestion
import com.verdenroz.corvid.core.model.SuggestionSenderKind
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.core.ui.provenance.initials
import com.verdenroz.corvid.core.ui.suggestion.HistoryStrip
import com.verdenroz.corvid.feature.filters.resources.Res
import com.verdenroz.corvid.feature.filters.resources.suggestion_add
import com.verdenroz.corvid.feature.filters.resources.suggestion_dismiss
import com.verdenroz.corvid.feature.filters.resources.suggestion_list_tag
import com.verdenroz.corvid.feature.filters.resources.suggestion_rule
import com.verdenroz.corvid.feature.filters.resources.suggestions_aside
import com.verdenroz.corvid.feature.filters.resources.suggestions_legend
import com.verdenroz.corvid.feature.filters.resources.suggestions_title
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.suggestionCards(
    state: FiltersUiState.Ready,
    onAccept: (RuleSuggestion) -> Unit,
    onDismiss: (RuleSuggestion) -> Unit,
) {
    if (state.suggestions.isEmpty()) return
    item {
        Column(
            Modifier.padding(horizontal = 16.dp).padding(top = 14.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(Res.string.suggestions_title), style = CorvidTheme.type.dayHeader)
                Text(stringResource(Res.string.suggestions_aside), style = CorvidTheme.type.meta, color = CorvidTheme.colors.inkFaint)
            }
            Text(stringResource(Res.string.suggestions_legend), style = CorvidTheme.type.meta, color = CorvidTheme.colors.inkMuted)
        }
    }
    val enabled = state.save !is SaveStatus.Saving
    items(state.suggestions, key = { it.id }) { suggestion ->
        SuggestionCard(suggestion, state.folders, onAccept = { if (enabled) onAccept(suggestion) }, onDismiss = { onDismiss(suggestion) })
    }
}

@Composable
private fun SuggestionCard(
    suggestion: RuleSuggestion,
    folders: List<MailboxRef>,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CorvidTheme.colors
    val caption = suggestion.caption(folders).asString()
    val frame = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp).fillMaxWidth()
    val quiet = Modifier.defaultMinSize(minHeight = 36.dp).clickable(role = Role.Button, onClick = onDismiss)
    val filled = Modifier.height(36.dp).background(colors.ink, CorvidShape.small).clickable(role = Role.Button, onClick = onAccept)
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = frame.border(1.dp, colors.line, CorvidShape.medium).padding(14.dp),
    ) {
        Sender(suggestion)
        RuleLine(suggestion.sentence(folders))
        if (suggestion.history.isNotEmpty()) HistoryStrip(suggestion.history, caption)
        Text(caption, style = CorvidTheme.type.meta, color = colors.inkMuted)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(quiet.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(Res.string.suggestion_dismiss), style = CorvidTheme.type.action, color = colors.inkMuted)
            }
            Box(filled.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(Res.string.suggestion_add), style = CorvidTheme.type.action, color = colors.surface)
            }
        }
    }
}

@Composable
private fun Sender(suggestion: RuleSuggestion) {
    val colors = CorvidTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.height(28.dp).defaultMinSize(minWidth = 28.dp).background(colors.chip, CorvidShape.small),
            contentAlignment = Alignment.Center,
        ) {
            Text(initials(suggestion.sender.value), style = CorvidTheme.type.chip, color = colors.ink)
        }
        Text(suggestion.sender.value, style = CorvidTheme.type.name, maxLines = 1, modifier = Modifier.weight(1f))
        if (suggestion.sender.kind == SuggestionSenderKind.List) {
            Text(stringResource(Res.string.suggestion_list_tag), style = CorvidTheme.type.meta, color = colors.inkFaint)
        }
    }
}

/** The sentence is a template with the two halves filled in, and each half is found in the result to set it in bold. */
@Composable
private fun RuleLine(sentence: RuleSentence) {
    val condition = sentence.condition.asString()
    val action = sentence.action.asString()
    val text = stringResource(Res.string.suggestion_rule, condition, action)
    val colors = CorvidTheme.colors
    Text(
        buildAnnotatedString {
            append(text)
            val at = text.indexOf(condition)
            val then = text.indexOf(action, startIndex = at + condition.length)
            val bold = SpanStyle(color = colors.ink, fontWeight = FontWeight.SemiBold)
            addStyle(bold, at, at + condition.length)
            addStyle(bold, then, then + action.length)
        },
        style = CorvidTheme.type.name.copy(fontWeight = FontWeight.Normal),
        color = colors.inkMuted,
    )
}
