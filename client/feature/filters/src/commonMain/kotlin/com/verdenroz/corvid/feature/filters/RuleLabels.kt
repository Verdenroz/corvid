package com.verdenroz.corvid.feature.filters

import com.verdenroz.corvid.core.model.ConditionField
import com.verdenroz.corvid.core.model.ConditionOperator
import com.verdenroz.corvid.core.model.FilterAction
import com.verdenroz.corvid.core.model.FilterCondition
import com.verdenroz.corvid.core.model.FilterRule
import com.verdenroz.corvid.core.model.MailboxRef
import com.verdenroz.corvid.core.model.MatchMode

internal fun MatchMode.label(): String = if (this == MatchMode.All) "all" else "any"

internal fun ConditionField.label(): String =
    when (this) {
        ConditionField.From -> "From"
        ConditionField.ToOrCc -> "To or Cc"
        ConditionField.Subject -> "Subject"
        ConditionField.ListId -> "List-Id"
    }

internal fun ConditionOperator.label(): String =
    when (this) {
        ConditionOperator.Contains -> "contains"
        ConditionOperator.Is -> "is"
        ConditionOperator.DoesNotContain -> "does not contain"
    }

internal fun FilterAction.label(): String =
    when (this) {
        is FilterAction.MoveToFolder -> "Move to folder"
        is FilterAction.AddLabel -> "Add label"
        is FilterAction.MarkRead -> "Mark read"
        is FilterAction.Star -> "Star"
        is FilterAction.MoveToTrash -> "Move to Trash"
    }

internal fun FilterAction.summary(): String =
    when (this) {
        is FilterAction.MoveToFolder -> "Move to ${folder.displayPath()}"
        is FilterAction.AddLabel -> "Add label $label"
        else -> label()
    }

internal fun FilterCondition.summary(): String = "${field.label()} ${operator.label()} \"$value\""

internal fun FilterRule.summary(): String {
    val joiner = if (match == MatchMode.All) " and " else " or "
    return conditions.joinToString(joiner) { it.summary() } + ": " + actions.joinToString(", ") { it.summary() }
}

internal fun MailboxRef.displayPath(): String = path.joinToString(" / ")
