package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.model.ConditionField
import com.verdenroz.corvid.core.model.ConditionOperator
import com.verdenroz.corvid.core.model.CorvidKeywords
import com.verdenroz.corvid.core.model.FilterAction
import com.verdenroz.corvid.core.model.FilterCondition
import com.verdenroz.corvid.core.model.FilterRule
import com.verdenroz.corvid.core.model.MatchMode
import com.verdenroz.corvid.core.model.isComplete

/**
 * The Sieve is output only: the rule list rides in the rules line and is never
 * recovered from the Sieve below it. Flag actions run before a rule's
 * `fileinto` so the filed copy carries them, and a flag set by a later rule
 * does not reach a message an earlier rule already filed. A disabled or
 * incomplete rule stays in the rules line and leaves the script.
 * [trashId] must be set when an enabled rule moves to Trash.
 */
internal class RulesSection(
    private val rules: List<FilterRule>,
    private val trashId: String?,
) : ScriptSection {
    override val requires = listOf("fileinto", "imap4flags", "mailboxid")
    override val metadata get() = rulesLine(rules)
    override val sieve get() = rules.filter { it.enabled && it.isComplete }.joinToString("\n") { it.toSieve(trashId) }
}

private fun FilterRule.toSieve(trashId: String?): String {
    val (fileActions, flagActions) = actions.partition { it is FilterAction.MoveToFolder || it is FilterAction.MoveToTrash }
    val lines =
        flagActions.map { "addflag ${quote(it.flag())};" } +
            fileActions.map { it.fileinto(trashId) } +
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

private fun FilterAction.flag(): String =
    when (this) {
        is FilterAction.MarkRead -> "\\Seen"
        is FilterAction.Star -> "\\Flagged"
        is FilterAction.AddLabel -> CorvidKeywords.ruleKeyword(label)
        else -> error("not a flag action: $this")
    }

/** The second argument is the name Sieve falls back to when the id no longer exists. */
private fun FilterAction.fileinto(trashId: String?): String =
    when (this) {
        is FilterAction.MoveToFolder -> "fileinto :mailboxid ${quote(folder.id)} ${quote(folder.path.joinToString("/"))};"
        is FilterAction.MoveToTrash -> "fileinto :mailboxid ${quote(checkNotNull(trashId) { "no trash folder" })} ${quote("Trash")};"
        else -> error("not a filing action: $this")
    }

/** RFC 5228 quoted string: backslash and double quote are the only characters that need escaping. */
internal fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
