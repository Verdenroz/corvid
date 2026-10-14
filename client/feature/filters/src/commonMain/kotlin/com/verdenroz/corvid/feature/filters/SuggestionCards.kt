package com.verdenroz.corvid.feature.filters

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.CorvidIcons
import com.verdenroz.corvid.core.designsystem.component.PillButton
import com.verdenroz.corvid.core.model.RuleSuggestion
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.feature.filters.resources.Res
import com.verdenroz.corvid.feature.filters.resources.suggestion_add
import com.verdenroz.corvid.feature.filters.resources.suggestion_dismiss
import com.verdenroz.corvid.feature.filters.resources.suggestions_title
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.suggestionCards(
    state: FiltersUiState.Ready,
    onAccept: (RuleSuggestion) -> Unit,
    onDismiss: (RuleSuggestion) -> Unit,
) {
    if (state.suggestions.isEmpty()) return
    item {
        Text(
            stringResource(Res.string.suggestions_title),
            style = CorvidTheme.type.name,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        )
    }
    items(state.suggestions, key = { it.id }) { suggestion ->
        val summary = suggestion.summary(state.folders) ?: return@items
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(summary.asString(), style = CorvidTheme.type.body)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val enabled = state.save !is SaveStatus.Saving
                PillButton(
                    stringResource(Res.string.suggestion_add),
                    CorvidIcons.Check,
                    { if (enabled) onAccept(suggestion) },
                    height = 28.dp,
                )
                PillButton(stringResource(Res.string.suggestion_dismiss), CorvidIcons.Close, { onDismiss(suggestion) }, height = 28.dp)
            }
        }
        HorizontalDivider(color = CorvidTheme.colors.line)
    }
}
