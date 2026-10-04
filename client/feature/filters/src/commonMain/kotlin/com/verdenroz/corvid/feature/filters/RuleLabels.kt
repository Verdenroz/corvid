package com.verdenroz.corvid.feature.filters

import androidx.compose.runtime.Composable
import com.verdenroz.corvid.core.model.ConditionField
import com.verdenroz.corvid.core.model.ConditionOperator
import com.verdenroz.corvid.core.model.FilterAction
import com.verdenroz.corvid.core.model.FilterCondition
import com.verdenroz.corvid.core.model.FilterRule
import com.verdenroz.corvid.core.model.MailboxRef
import com.verdenroz.corvid.core.model.MatchMode
import com.verdenroz.corvid.feature.filters.resources.Res
import com.verdenroz.corvid.feature.filters.resources.action_add_label
import com.verdenroz.corvid.feature.filters.resources.action_mark_read
import com.verdenroz.corvid.feature.filters.resources.action_move_to_folder
import com.verdenroz.corvid.feature.filters.resources.action_move_to_trash
import com.verdenroz.corvid.feature.filters.resources.action_star
import com.verdenroz.corvid.feature.filters.resources.field_from
import com.verdenroz.corvid.feature.filters.resources.field_list_id
import com.verdenroz.corvid.feature.filters.resources.field_subject
import com.verdenroz.corvid.feature.filters.resources.field_to_or_cc
import com.verdenroz.corvid.feature.filters.resources.match_all
import com.verdenroz.corvid.feature.filters.resources.match_any
import com.verdenroz.corvid.feature.filters.resources.operator_contains
import com.verdenroz.corvid.feature.filters.resources.operator_does_not_contain
import com.verdenroz.corvid.feature.filters.resources.operator_is
import com.verdenroz.corvid.feature.filters.resources.summary_add_label
import com.verdenroz.corvid.feature.filters.resources.summary_and
import com.verdenroz.corvid.feature.filters.resources.summary_condition
import com.verdenroz.corvid.feature.filters.resources.summary_move_to
import com.verdenroz.corvid.feature.filters.resources.summary_or
import com.verdenroz.corvid.feature.filters.resources.summary_rule
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun MatchMode.label(): String = stringResource(if (this == MatchMode.All) Res.string.match_all else Res.string.match_any)

@Composable
internal fun ConditionField.label(): String =
    stringResource(
        when (this) {
            ConditionField.From -> Res.string.field_from
            ConditionField.ToOrCc -> Res.string.field_to_or_cc
            ConditionField.Subject -> Res.string.field_subject
            ConditionField.ListId -> Res.string.field_list_id
        },
    )

@Composable
internal fun ConditionOperator.label(): String =
    stringResource(
        when (this) {
            ConditionOperator.Contains -> Res.string.operator_contains
            ConditionOperator.Is -> Res.string.operator_is
            ConditionOperator.DoesNotContain -> Res.string.operator_does_not_contain
        },
    )

@Composable
internal fun FilterAction.label(): String =
    stringResource(
        when (this) {
            is FilterAction.MoveToFolder -> Res.string.action_move_to_folder
            is FilterAction.AddLabel -> Res.string.action_add_label
            is FilterAction.MarkRead -> Res.string.action_mark_read
            is FilterAction.Star -> Res.string.action_star
            is FilterAction.MoveToTrash -> Res.string.action_move_to_trash
        },
    )

@Composable
internal fun FilterAction.summary(): String =
    when (this) {
        is FilterAction.MoveToFolder -> stringResource(Res.string.summary_move_to, folder.displayPath())
        is FilterAction.AddLabel -> stringResource(Res.string.summary_add_label, label)
        else -> label()
    }

@Composable
internal fun FilterCondition.summary(): String = stringResource(Res.string.summary_condition, field.label(), operator.label(), value)

@Composable
internal fun FilterRule.summary(): String {
    val joiner = " ${stringResource(if (match == MatchMode.All) Res.string.summary_and else Res.string.summary_or)} "
    return stringResource(
        Res.string.summary_rule,
        conditions.map { it.summary() }.joinToString(joiner),
        actions.map { it.summary() }.joinToString(", "),
    )
}

internal fun MailboxRef.displayPath(): String = path.joinToString(" / ")
