package com.verdenroz.corvid.feature.filters

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.model.FilterRule
import com.verdenroz.corvid.core.model.MailboxRef
import com.verdenroz.corvid.core.model.MatchMode
import com.verdenroz.corvid.core.model.isComplete
import com.verdenroz.corvid.feature.filters.resources.Res
import com.verdenroz.corvid.feature.filters.resources.filters_cancel
import com.verdenroz.corvid.feature.filters.resources.filters_match
import com.verdenroz.corvid.feature.filters.resources.filters_name
import com.verdenroz.corvid.feature.filters.resources.filters_of_conditions
import com.verdenroz.corvid.feature.filters.resources.filters_save
import com.verdenroz.corvid.feature.filters.resources.filters_stop
import com.verdenroz.corvid.feature.filters.resources.filters_then
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun RuleEditor(
    rule: FilterRule,
    folders: List<MailboxRef>,
    onChange: (FilterRule) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = rule.name,
            onValueChange = { onChange(rule.copy(name = it)) },
            label = { Text(stringResource(Res.string.filters_name)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.filters_match))
            Picker(rule.match.label(), MatchMode.entries, { it.label() }) { onChange(rule.copy(match = it)) }
            Text(stringResource(Res.string.filters_of_conditions))
        }
        ConditionsSection(rule.conditions) { onChange(rule.copy(conditions = it)) }

        Text(stringResource(Res.string.filters_then), style = MaterialTheme.typography.titleSmall)
        ActionsSection(rule.actions, folders) { onChange(rule.copy(actions = it)) }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = rule.stop, onCheckedChange = { onChange(rule.copy(stop = it)) })
            Text(stringResource(Res.string.filters_stop))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSave, enabled = rule.isComplete) { Text(stringResource(Res.string.filters_save)) }
            TextButton(onClick = onCancel) { Text(stringResource(Res.string.filters_cancel)) }
        }
    }
}
