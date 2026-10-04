package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.model.ConditionField
import com.verdenroz.corvid.core.model.ConditionOperator
import com.verdenroz.corvid.core.model.FilterAction
import com.verdenroz.corvid.core.model.FilterCondition
import com.verdenroz.corvid.core.model.FilterRule
import com.verdenroz.corvid.core.model.MatchMode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Bumped by any new field or enum value in the rules format: a client that does
 * not know it must refuse the line rather than read it as something else.
 */
private const val CURRENT_VERSION = 1
private val RULES_LINE = Regex("^# corvid-rules v(\\d+) (.*)$")
private val RULES = ListSerializer(RuleDto.serializer())

sealed class FiltersFormatException(message: String) : Exception(message) {
    /** The `corvid` script has no rules line, or one this client cannot read. */
    class Unreadable : FiltersFormatException("the corvid script has no rule list this client can read")

    class Newer : FiltersFormatException("the filters were saved by a newer Corvid, update the app to edit them")
}

/** The one `#` line holding the rule list. Compact JSON escapes every control character, so it never spans lines. */
internal fun rulesLine(rules: List<FilterRule>): String =
    "# corvid-rules v$CURRENT_VERSION " + Json.encodeToString(RULES, rules.map { it.toDto() })

internal fun parseRules(script: String): List<FilterRule> {
    val line = script.lineSequence().firstNotNullOfOrNull { RULES_LINE.matchEntire(it) } ?: throw FiltersFormatException.Unreadable()
    val version = line.groupValues[1].toIntOrNull() ?: throw FiltersFormatException.Unreadable()
    return when {
        version == CURRENT_VERSION -> decode(line.groupValues[2])
        version > CURRENT_VERSION -> throw FiltersFormatException.Newer()
        else -> throw FiltersFormatException.Unreadable()
    }
}

private fun decode(json: String): List<FilterRule> =
    try {
        Json.decodeFromString(RULES, json).map { it.toModel() }
    } catch (e: IllegalArgumentException) {
        throw FiltersFormatException.Unreadable()
    }

@Serializable
private data class RuleDto(
    val name: String,
    val enabled: Boolean,
    val match: MatchDto,
    val conditions: List<ConditionDto>,
    val actions: List<ActionDto>,
    val stop: Boolean,
)

@Serializable
private data class ConditionDto(
    val field: FieldDto,
    val operator: OperatorDto,
    val value: String,
)

@Serializable
private sealed class ActionDto {
    @Serializable
    @SerialName("MoveToFolder")
    data class MoveToFolder(val id: String, val name: String) : ActionDto()

    @Serializable
    @SerialName("AddLabel")
    data class AddLabel(val value: String) : ActionDto()

    @Serializable
    @SerialName("MarkRead")
    data object MarkRead : ActionDto()

    @Serializable
    @SerialName("Star")
    data object Star : ActionDto()

    @Serializable
    @SerialName("MoveToTrash")
    data object MoveToTrash : ActionDto()
}

@Serializable
private enum class MatchDto {
    @SerialName("All")
    ALL,

    @SerialName("Any")
    ANY,
}

@Serializable
private enum class FieldDto {
    @SerialName("From")
    FROM,

    @SerialName("ToOrCc")
    TO_OR_CC,

    @SerialName("Subject")
    SUBJECT,

    @SerialName("ListId")
    LIST_ID,
}

@Serializable
private enum class OperatorDto {
    @SerialName("Contains")
    CONTAINS,

    @SerialName("Is")
    IS,

    @SerialName("DoesNotContain")
    DOES_NOT_CONTAIN,
}

private fun FilterRule.toDto() =
    RuleDto(
        name = name,
        enabled = enabled,
        match = if (match == MatchMode.All) MatchDto.ALL else MatchDto.ANY,
        conditions = conditions.map { it.toDto() },
        actions = actions.map { it.toDto() },
        stop = stop,
    )

private fun FilterCondition.toDto() =
    ConditionDto(
        field =
            when (field) {
                ConditionField.From -> FieldDto.FROM
                ConditionField.ToOrCc -> FieldDto.TO_OR_CC
                ConditionField.Subject -> FieldDto.SUBJECT
                ConditionField.ListId -> FieldDto.LIST_ID
            },
        operator =
            when (operator) {
                ConditionOperator.Contains -> OperatorDto.CONTAINS
                ConditionOperator.Is -> OperatorDto.IS
                ConditionOperator.DoesNotContain -> OperatorDto.DOES_NOT_CONTAIN
            },
        value = value,
    )

private fun FilterAction.toDto(): ActionDto =
    when (this) {
        is FilterAction.MoveToFolder -> ActionDto.MoveToFolder(mailboxId, mailboxName)
        is FilterAction.AddLabel -> ActionDto.AddLabel(label)
        is FilterAction.MarkRead -> ActionDto.MarkRead
        is FilterAction.Star -> ActionDto.Star
        is FilterAction.MoveToTrash -> ActionDto.MoveToTrash
    }

private fun RuleDto.toModel() =
    FilterRule(
        name = name,
        enabled = enabled,
        match = if (match == MatchDto.ALL) MatchMode.All else MatchMode.Any,
        conditions = conditions.map { it.toModel() },
        actions = actions.map { it.toModel() },
        stop = stop,
    )

private fun ConditionDto.toModel() =
    FilterCondition(
        field =
            when (field) {
                FieldDto.FROM -> ConditionField.From
                FieldDto.TO_OR_CC -> ConditionField.ToOrCc
                FieldDto.SUBJECT -> ConditionField.Subject
                FieldDto.LIST_ID -> ConditionField.ListId
            },
        operator =
            when (operator) {
                OperatorDto.CONTAINS -> ConditionOperator.Contains
                OperatorDto.IS -> ConditionOperator.Is
                OperatorDto.DOES_NOT_CONTAIN -> ConditionOperator.DoesNotContain
            },
        value = value,
    )

private fun ActionDto.toModel(): FilterAction =
    when (this) {
        is ActionDto.MoveToFolder -> FilterAction.MoveToFolder(id, name)
        is ActionDto.AddLabel -> FilterAction.AddLabel(value)
        is ActionDto.MarkRead -> FilterAction.MarkRead
        is ActionDto.Star -> FilterAction.Star
        is ActionDto.MoveToTrash -> FilterAction.MoveToTrash
    }
