package com.verdenroz.corvid.feature.filters

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.model.ConditionField
import com.verdenroz.corvid.core.model.ConditionOperator
import com.verdenroz.corvid.core.model.FilterAction
import com.verdenroz.corvid.core.model.FilterCondition
import com.verdenroz.corvid.core.model.FilterRule
import com.verdenroz.corvid.core.model.MailboxRef
import com.verdenroz.corvid.core.model.MatchMode
import com.verdenroz.corvid.core.model.isComplete

@Composable
internal fun RuleEditor(
    initial: FilterRule,
    folders: List<MailboxRef>,
    onSave: (FilterRule) -> Unit,
    onCancel: () -> Unit,
) {
    var rule by remember { mutableStateOf(initial) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = rule.name,
            onValueChange = { rule = rule.copy(name = it) },
            label = { Text("Name") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Match")
            Picker(if (rule.match == MatchMode.All) "all" else "any", MatchMode.entries, { if (it == MatchMode.All) "all" else "any" }) {
                rule = rule.copy(match = it)
            }
            Text("of these conditions")
        }
        rule.conditions.forEachIndexed { index, condition ->
            ConditionRow(
                condition = condition,
                canRemove = rule.conditions.size > 1,
                onChange = { changed -> rule = rule.copy(conditions = rule.conditions.replaced(index, changed)) },
                onRemove = { rule = rule.copy(conditions = rule.conditions.filterIndexed { i, _ -> i != index }) },
            )
        }
        TextButton(onClick = {
            rule = rule.copy(conditions = rule.conditions + FilterCondition(ConditionField.From, ConditionOperator.Contains, ""))
        }) { Text("Add condition") }

        Text("Then", style = MaterialTheme.typography.titleSmall)
        rule.actions.forEachIndexed { index, action ->
            ActionRow(
                action = action,
                folders = folders,
                canRemove = rule.actions.size > 1,
                onChange = { changed -> rule = rule.copy(actions = rule.actions.replaced(index, changed)) },
                onRemove = { rule = rule.copy(actions = rule.actions.filterIndexed { i, _ -> i != index }) },
            )
        }
        TextButton(onClick = { rule = rule.copy(actions = rule.actions + FilterAction.MarkRead) }) { Text("Add action") }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = rule.stop, onCheckedChange = { rule = rule.copy(stop = it) })
            Text("Stop processing later rules")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(rule) }, enabled = rule.isComplete) { Text("Save") }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun ConditionRow(
    condition: FilterCondition,
    canRemove: Boolean,
    onChange: (FilterCondition) -> Unit,
    onRemove: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Picker(condition.field.label(), ConditionField.entries, { it.label() }) { onChange(condition.copy(field = it)) }
            Picker(condition.operator.label(), ConditionOperator.entries, { it.label() }) { onChange(condition.copy(operator = it)) }
            if (canRemove) TextButton(onClick = onRemove) { Text("Remove") }
        }
        OutlinedTextField(
            value = condition.value,
            onValueChange = { onChange(condition.copy(value = it)) },
            label = { Text("Value") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ActionRow(
    action: FilterAction,
    folders: List<MailboxRef>,
    canRemove: Boolean,
    onChange: (FilterAction) -> Unit,
    onRemove: () -> Unit,
) {
    val kinds =
        listOf(
            FilterAction.MoveToFolder(folders.firstOrNull() ?: MailboxRef.None),
            FilterAction.AddLabel(""),
            FilterAction.MarkRead,
            FilterAction.Star,
            FilterAction.MoveToTrash,
        )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Picker(action.label(), kinds, { it.label() }) { onChange(it) }
            if (canRemove) TextButton(onClick = onRemove) { Text("Remove") }
        }
        when (action) {
            is FilterAction.MoveToFolder ->
                Picker(
                    selected = if (action.folder.path.isEmpty()) "Choose a folder" else action.folder.displayPath(),
                    options = folders,
                    label = { it.displayPath() },
                ) { onChange(FilterAction.MoveToFolder(it)) }
            is FilterAction.AddLabel ->
                OutlinedTextField(
                    value = action.label,
                    onValueChange = { onChange(FilterAction.AddLabel(it)) },
                    label = { Text("Label") },
                    modifier = Modifier.fillMaxWidth(),
                )
            else -> Unit
        }
    }
}

@Composable
private fun <T> Picker(
    selected: String,
    options: List<T>,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text(selected) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(label(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

private fun <T> List<T>.replaced(
    index: Int,
    item: T,
): List<T> = mapIndexed { i, old -> if (i == index) item else old }
