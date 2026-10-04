package com.verdenroz.corvid.feature.filters

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.model.ConditionField
import com.verdenroz.corvid.core.model.ConditionOperator
import com.verdenroz.corvid.core.model.FilterCondition

@Composable
internal fun ConditionsSection(
    conditions: List<FilterCondition>,
    onChange: (List<FilterCondition>) -> Unit,
) {
    conditions.forEachIndexed { index, condition ->
        ConditionRow(
            condition = condition,
            canRemove = conditions.size > 1,
            onChange = { onChange(conditions.replaced(index, it)) },
            onRemove = { onChange(conditions.removedAt(index)) },
        )
    }
    TextButton(onClick = { onChange(conditions + FilterCondition.blank()) }) { Text("Add condition") }
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
