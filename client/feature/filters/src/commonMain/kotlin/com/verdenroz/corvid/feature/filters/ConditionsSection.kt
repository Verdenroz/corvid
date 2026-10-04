package com.verdenroz.corvid.feature.filters

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.designsystem.component.CorvidIcons
import com.verdenroz.corvid.core.designsystem.component.CorvidTextField
import com.verdenroz.corvid.core.designsystem.component.PillButton
import com.verdenroz.corvid.core.model.ConditionField
import com.verdenroz.corvid.core.model.ConditionOperator
import com.verdenroz.corvid.core.model.FilterCondition
import com.verdenroz.corvid.feature.filters.resources.Res
import com.verdenroz.corvid.feature.filters.resources.filters_add_condition
import com.verdenroz.corvid.feature.filters.resources.filters_remove
import com.verdenroz.corvid.feature.filters.resources.filters_value
import org.jetbrains.compose.resources.stringResource

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
    PillButton(stringResource(Res.string.filters_add_condition), CorvidIcons.Plus, {
        onChange(conditions + FilterCondition.blank())
    }, height = 32.dp)
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
            if (canRemove) PillButton(stringResource(Res.string.filters_remove), null, onRemove, height = 32.dp)
        }
        CorvidTextField(
            condition.value,
            { onChange(condition.copy(value = it)) },
            stringResource(Res.string.filters_value),
            Modifier.fillMaxWidth(),
        )
    }
}
