package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.model.ConditionField
import com.verdenroz.corvid.core.model.ConditionOperator
import com.verdenroz.corvid.core.model.FilterAction
import com.verdenroz.corvid.core.model.FilterCondition
import com.verdenroz.corvid.core.model.FilterRule
import com.verdenroz.corvid.core.model.MailboxRef
import com.verdenroz.corvid.core.model.MatchMode
import com.verdenroz.corvid.core.model.RuleSuggestion
import com.verdenroz.corvid.core.model.SuggestionAction
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
    suspend fun fetch(): List<RuleSuggestion> = suggestions.list()

    /** The [fetched] suggestions worth showing against [current]: none whose folder is gone or that a rule already does. */
    suspend fun pending(
        fetched: List<RuleSuggestion>,
        current: FilterSet,
    ): List<RuleSuggestion> {
        val archiveId = if (fetched.any { it.action == SuggestionAction.Archive }) mailboxes.mailboxId(ARCHIVE) else null
        return fetched.filter { suggestion ->
            val action = suggestion.action
            (action !is SuggestionAction.Folder || current.folders.any { it.id == action.mailboxId }) &&
                current.rules.none { it.covers(suggestion, archiveId) }
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
        if (result is SaveResult.Saved) {
            // The rule is written and pending() hides the card once a rule covers it, so a failed post changes nothing.
            try {
                suggestions.accept(suggestion.id)
            } catch (e: JmapException) {
                return result
            }
        }
        return result
    }

    suspend fun dismiss(suggestion: RuleSuggestion) = suggestions.dismiss(suggestion.id)

    private suspend fun action(
        suggestion: RuleSuggestion,
        folders: List<MailboxRef>,
    ): FilterAction? =
        when (val action = suggestion.action) {
            SuggestionAction.Trash -> FilterAction.MoveToTrash
            is SuggestionAction.Folder -> folders.firstOrNull { it.id == action.mailboxId }?.let(FilterAction::MoveToFolder)
            SuggestionAction.Archive -> archiveFolder(folders)?.let(FilterAction::MoveToFolder)
        }

    private suspend fun archiveFolder(known: List<MailboxRef>): MailboxRef? {
        val id = mailboxes.archiveMailboxId()
        return known.firstOrNull { it.id == id } ?: mailboxes.mailboxes().refs().firstOrNull { it.id == id }
    }

    private fun missingFolder(suggestion: RuleSuggestion) =
        uiText(if (suggestion.action == SuggestionAction.Archive) Res.string.no_archive_folder else Res.string.suggestion_folder_gone)
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

private fun FilterRule.covers(
    suggestion: RuleSuggestion,
    archiveId: String?,
): Boolean =
    enabled &&
        (conditions.size == 1 || match == MatchMode.Any) &&
        suggestion.condition() in conditions &&
        actions.any { suggestion.action.files(it, archiveId) }

private fun SuggestionAction.files(
    filing: FilterAction,
    archiveId: String?,
): Boolean =
    when (this) {
        SuggestionAction.Trash -> filing == FilterAction.MoveToTrash
        SuggestionAction.Archive -> filing is FilterAction.MoveToFolder && filing.folder.id == archiveId
        is SuggestionAction.Folder -> filing is FilterAction.MoveToFolder && filing.folder.id == mailboxId
    }
