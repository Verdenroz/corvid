package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.model.ConditionField
import com.verdenroz.corvid.core.model.ConditionOperator
import com.verdenroz.corvid.core.model.FilterAction
import com.verdenroz.corvid.core.model.FilterCondition
import com.verdenroz.corvid.core.model.FilterRule
import com.verdenroz.corvid.core.model.MailboxRef
import com.verdenroz.corvid.core.model.MatchMode
import com.verdenroz.corvid.core.model.RuleSuggestion
import com.verdenroz.corvid.core.model.SuggestionActionKind
import com.verdenroz.corvid.core.model.SuggestionSenderKind
import com.verdenroz.corvid.core.model.refs
import com.verdenroz.corvid.core.text.SystemTextResolver
import com.verdenroz.corvid.core.text.TextResolver
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.resources.Res
import com.verdenroz.corvid.core.text.resources.no_archive_folder
import com.verdenroz.corvid.core.text.resources.rule_name_from
import com.verdenroz.corvid.core.text.resources.rule_name_list
import com.verdenroz.corvid.core.text.resources.suggestion_folder_gone
import com.verdenroz.corvid.core.text.uiText

/** Turns the server's suggestions into filter rules, and tells the server what became of each. */
class SuggestedRules(
    private val suggestions: SuggestionRepository,
    private val filters: FiltersRepository,
    private val mailboxes: MailboxRepository,
    private val text: TextResolver = SystemTextResolver,
) {
    /** The suggestions worth showing against [current]: none whose folder is gone or that a rule already does. */
    suspend fun pending(current: FilterSet): List<RuleSuggestion> {
        val all = suggestions.list()
        if (all.isEmpty()) return all
        val archiveId = mailboxes.mailboxId(ARCHIVE)
        return all.filter { suggestion ->
            val folderId = suggestion.folderId(archiveId)
            (suggestion.action.kind != SuggestionActionKind.Folder || current.folders.any { it.id == folderId }) &&
                current.rules.none { it.covers(suggestion, folderId) }
        }
    }

    /** Appends the rule to the saved ones; the server hears of it only once the rules are written. */
    suspend fun accept(
        suggestion: RuleSuggestion,
        replaceForeign: Boolean,
    ): SaveResult {
        val set = filters.load()
        val action = action(suggestion, set.folders) ?: return SaveResult.Rejected(missingFolder(suggestion))
        val result = filters.save(set.rules + suggestion.toRule(action, text.resolve(suggestion.ruleName())), replaceForeign)
        if (result is SaveResult.Saved) suggestions.accept(suggestion.id)
        return result
    }

    suspend fun dismiss(suggestion: RuleSuggestion) = suggestions.dismiss(suggestion.id)

    private suspend fun action(
        suggestion: RuleSuggestion,
        folders: List<MailboxRef>,
    ): FilterAction? =
        when (suggestion.action.kind) {
            SuggestionActionKind.Trash -> FilterAction.MoveToTrash
            SuggestionActionKind.Folder -> folders.firstOrNull { it.id == suggestion.action.mailboxId }?.let(FilterAction::MoveToFolder)
            SuggestionActionKind.Archive -> {
                val id = mailboxes.archiveMailboxId()
                mailboxes.mailboxes().refs().firstOrNull { it.id == id }?.let(FilterAction::MoveToFolder)
            }
        }

    private fun missingFolder(suggestion: RuleSuggestion) =
        uiText(
            if (suggestion.action.kind == SuggestionActionKind.Archive) Res.string.no_archive_folder else Res.string.suggestion_folder_gone,
        )
}

private fun RuleSuggestion.condition() =
    when (sender.kind) {
        SuggestionSenderKind.From -> FilterCondition(ConditionField.From, ConditionOperator.Is, sender.value)
        SuggestionSenderKind.List -> FilterCondition(ConditionField.ListId, ConditionOperator.Contains, "<${sender.value}>")
    }

private fun RuleSuggestion.ruleName() =
    uiText(
        if (sender.kind == SuggestionSenderKind.From) Res.string.rule_name_from else Res.string.rule_name_list,
        UiText.FromServer(sender.value),
    )

private fun RuleSuggestion.toRule(
    filing: FilterAction,
    name: String,
) = FilterRule(
    name = name,
    enabled = true,
    match = MatchMode.All,
    conditions = listOf(condition()),
    actions = listOf(filing),
    stop = false,
)

private fun RuleSuggestion.folderId(archiveId: String?) =
    when (action.kind) {
        SuggestionActionKind.Folder -> action.mailboxId
        SuggestionActionKind.Archive -> archiveId
        SuggestionActionKind.Trash -> null
    }

private fun FilterRule.covers(
    suggestion: RuleSuggestion,
    folderId: String?,
): Boolean =
    enabled &&
        (conditions.size == 1 || match == MatchMode.Any) &&
        suggestion.condition() in conditions &&
        actions.any {
            when (suggestion.action.kind) {
                SuggestionActionKind.Trash -> it == FilterAction.MoveToTrash
                else -> folderId != null && it is FilterAction.MoveToFolder && it.folder.id == folderId
            }
        }
