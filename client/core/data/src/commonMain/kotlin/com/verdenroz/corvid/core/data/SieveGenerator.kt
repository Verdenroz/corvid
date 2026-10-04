package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.model.ConditionField
import com.verdenroz.corvid.core.model.ConditionOperator
import com.verdenroz.corvid.core.model.FilterAction
import com.verdenroz.corvid.core.model.FilterCondition
import com.verdenroz.corvid.core.model.FilterRule
import com.verdenroz.corvid.core.model.MatchMode
import com.verdenroz.corvid.core.model.isComplete

internal const val RULE_KEYWORD_PREFIX = "corvid-rule-"

/**
 * The Sieve is output only: the rule list rides in the rules line and is never
 * recovered from the Sieve below it. Flag actions run before a rule's
 * `fileinto` so the filed copy carries them, and a flag set by a later rule
 * does not reach a message an earlier rule already filed. A disabled or
 * incomplete rule stays in the rules line and leaves the script.
 * [trash] must be set when an enabled rule moves to Trash.
 */
internal class RulesSection(
    private val rules: List<FilterRule>,
    private val trash: FilterAction.MoveToFolder?,
) : ScriptSection {
    override val requires = listOf("fileinto", "imap4flags", "mailboxid")
    override val metadata get() = rulesLine(rules)
    override val sieve get() = rules.filter { it.enabled && it.isComplete }.joinToString("\n") { it.toSieve(trash) }
}

private fun FilterRule.toSieve(trash: FilterAction.MoveToFolder?): String {
    val (fileActions, flagActions) = actions.partition { it is FilterAction.MoveToFolder || it is FilterAction.MoveToTrash }
    val lines =
        flagActions.map { "addflag ${quote(it.flag())};" } +
            fileActions.map { it.folder(trash).fileinto() } +
            listOfNotNull("stop;".takeIf { stop })
    val combinator = if (match == MatchMode.All) "allof" else "anyof"
    return "if $combinator (${conditions.joinToString(", ") { it.toSieve() }}) {\n" +
        lines.joinToString("") { "  $it\n" } +
        "}"
}

private fun FilterCondition.toSieve(): String {
    val test =
        when {
            operator == ConditionOperator.Is && field.isAddress() -> "address :is"
            operator == ConditionOperator.Is -> "header :is"
            else -> "header :contains"
        }
    val negation = if (operator == ConditionOperator.DoesNotContain) "not " else ""
    return "$negation$test ${field.headers()} ${quote(value)}"
}

private fun ConditionField.isAddress() = this == ConditionField.From || this == ConditionField.ToOrCc

private fun ConditionField.headers(): String =
    when (this) {
        ConditionField.From -> quote("from")
        ConditionField.ToOrCc -> "[${quote("to")}, ${quote("cc")}]"
        ConditionField.Subject -> quote("subject")
        ConditionField.ListId -> quote("list-id")
    }

private fun FilterAction.MoveToFolder.fileinto() = "fileinto :mailboxid ${quote(mailboxId)} ${quote(mailboxName)};"

private fun FilterAction.flag(): String =
    when (this) {
        is FilterAction.MarkRead -> "\\Seen"
        is FilterAction.Star -> "\\Flagged"
        is FilterAction.AddLabel -> ruleKeyword(label)
        else -> error("not a flag action: $this")
    }

private fun FilterAction.folder(trash: FilterAction.MoveToFolder?): FilterAction.MoveToFolder =
    when (this) {
        is FilterAction.MoveToFolder -> this
        is FilterAction.MoveToTrash -> checkNotNull(trash) { "no trash folder" }
        else -> error("not a filing action: $this")
    }

/** JMAP keywords are ASCII with no spaces or `(){]%*"\`, so anything else in a label becomes `-`. */
internal fun ruleKeyword(label: String): String =
    RULE_KEYWORD_PREFIX + label.lowercase().map { if (it in 'a'..'z' || it in '0'..'9' || it in "-_.") it else '-' }.joinToString("")

/** RFC 5228 quoted string: backslash and double quote are the only characters that need escaping. */
internal fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
