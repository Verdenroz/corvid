package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.model.ConditionField
import com.verdenroz.corvid.core.model.ConditionOperator
import com.verdenroz.corvid.core.model.FilterAction
import com.verdenroz.corvid.core.model.FilterCondition
import com.verdenroz.corvid.core.model.FilterRule
import com.verdenroz.corvid.core.model.MatchMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val RULES_PREFIX = "# corvid-rules v1 "

/** The `corvid-filters` script has no rules line, or one this client cannot read. */
class FiltersFormatException : Exception("the corvid-filters script has no rule list this client can read")

/** The one `#` line holding the rule list. Compact JSON escapes every control character, so it never spans lines. */
internal fun rulesLine(rules: List<FilterRule>): String = RULES_PREFIX + buildJsonArray { rules.forEach { add(it.toJson()) } }

/** Null when the script carries no rules line of this version; a line that is there but unreadable throws. */
internal fun parseRules(script: String): List<FilterRule>? =
    script.lineSequence()
        .firstOrNull { it.startsWith(RULES_PREFIX) }
        ?.removePrefix(RULES_PREFIX)
        ?.let { line ->
            try {
                Json.parseToJsonElement(line).jsonArray.map { it.jsonObject.toRule() }
            } catch (e: RuntimeException) {
                throw FiltersFormatException()
            }
        }

private fun FilterRule.toJson() =
    buildJsonObject {
        put("name", name)
        put("enabled", enabled)
        put("match", match.name)
        put("conditions", JsonArray(conditions.map { it.toJson() }))
        put("actions", JsonArray(actions.map { it.toJson() }))
        put("stop", stop)
    }

private fun FilterCondition.toJson() =
    buildJsonObject {
        put("field", field.name)
        put("operator", operator.name)
        put("value", value)
    }

private fun FilterAction.toJson() =
    buildJsonObject {
        when (this@toJson) {
            is FilterAction.MoveToFolder -> {
                put("type", "MoveToFolder")
                put("id", mailboxId)
                put("value", mailboxName)
            }
            is FilterAction.AddLabel -> {
                put("type", "AddLabel")
                put("value", label)
            }
            is FilterAction.MarkRead -> put("type", "MarkRead")
            is FilterAction.Star -> put("type", "Star")
            is FilterAction.MoveToTrash -> put("type", "MoveToTrash")
        }
    }

private fun JsonObject.toRule() =
    FilterRule(
        name = getValue("name").jsonPrimitive.content,
        enabled = getValue("enabled").jsonPrimitive.boolean,
        match = MatchMode.valueOf(getValue("match").jsonPrimitive.content),
        conditions = getValue("conditions").jsonArray.map { it.jsonObject.toCondition() },
        actions = getValue("actions").jsonArray.map { it.jsonObject.toAction() },
        stop = getValue("stop").jsonPrimitive.boolean,
    )

private fun JsonObject.toCondition() =
    FilterCondition(
        field = ConditionField.valueOf(getValue("field").jsonPrimitive.content),
        operator = ConditionOperator.valueOf(getValue("operator").jsonPrimitive.content),
        value = getValue("value").jsonPrimitive.content,
    )

private fun JsonObject.toAction(): FilterAction {
    val value = get("value")?.jsonPrimitive?.contentOrNull
    return when (val type = getValue("type").jsonPrimitive.content) {
        "MoveToFolder" -> FilterAction.MoveToFolder(getValue("id").jsonPrimitive.content, value.orEmpty())
        "AddLabel" -> FilterAction.AddLabel(value.orEmpty())
        "MarkRead" -> FilterAction.MarkRead
        "Star" -> FilterAction.Star
        "MoveToTrash" -> FilterAction.MoveToTrash
        else -> error("unknown filter action $type")
    }
}
