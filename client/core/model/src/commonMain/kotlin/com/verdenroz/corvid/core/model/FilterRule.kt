package com.verdenroz.corvid.core.model

enum class MatchMode { All, Any }

enum class ConditionField { From, ToOrCc, Subject, ListId }

enum class ConditionOperator { Contains, Is, DoesNotContain }

data class FilterCondition(
    val field: ConditionField,
    val operator: ConditionOperator,
    val value: String,
) {
    companion object {
        fun blank() = FilterCondition(ConditionField.From, ConditionOperator.Contains, "")
    }
}

sealed interface FilterAction {
    /** Filed by the folder's id, so a rename or a nested folder still resolves. */
    data class MoveToFolder(val folder: MailboxRef) : FilterAction

    /** Written as the keyword `corvid-rule-<label>`, so rule output never reads as the user's own label. */
    data class AddLabel(val label: String) : FilterAction

    data object MarkRead : FilterAction

    data object Star : FilterAction

    data object MoveToTrash : FilterAction
}

data class FilterRule(
    val name: String,
    val enabled: Boolean,
    val match: MatchMode,
    val conditions: List<FilterCondition>,
    val actions: List<FilterAction>,
    val stop: Boolean,
) {
    companion object {
        fun blank() = FilterRule("", true, MatchMode.All, listOf(FilterCondition.blank()), listOf(FilterAction.MarkRead), stop = false)
    }
}

/** A rule missing a condition, an action, or an action's target cannot compile to valid Sieve. */
val FilterRule.isComplete: Boolean
    get() =
        name.isNotBlank() &&
            conditions.isNotEmpty() &&
            conditions.all { it.value.isNotBlank() } &&
            actions.isNotEmpty() &&
            actions.all { it.isComplete }

private val FilterAction.isComplete: Boolean
    get() =
        when (this) {
            is FilterAction.MoveToFolder -> folder.id.isNotBlank()
            is FilterAction.AddLabel -> label.isNotBlank()
            else -> true
        }
